package com.defold.extender;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.ExtenderClientCache;
import com.defold.extender.client.ExtenderClientException;
import com.defold.extender.client.ExtenderResource;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.yaml.snakeyaml.Yaml;

import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment= SpringBootTest.WebEnvironment.RANDOM_PORT, value = {"extender.sdk-location = test-data/sdk", "extender.sdk-cache-size = 3"})
public class ExtenderTest {

    private static class FileExtenderResource implements ExtenderResource {

        private File file = null;
        private String filePath;
        private String fileAbsPath;
        FileExtenderResource(String filePath) {
            this.file = new File(filePath);
            this.filePath = file.getPath();
            this.fileAbsPath = file.getAbsolutePath();
        }

        FileExtenderResource(File file) {
            this.file = file;
            this.filePath = file.getPath();
            this.fileAbsPath = file.getAbsolutePath();
        }

        @Override
        public byte[] sha1() throws IOException {
            return new byte[0];
        }

        @Override
        public String getAbsPath() {
            return fileAbsPath;
        }

        @Override
        public String getPath() {
            return filePath;
        }

        @Override
        public byte[] getContent() throws IOException {
            return Files.readAllBytes(this.file.toPath());
        }

        @Override
        public long getLastModified() {
            return file.lastModified();
        }
    }

    private static List<ExtenderResource> getExtensionSource(File root, String platform) throws IOException {
        List<ExtenderResource> source = new ArrayList<>();
        List<File> extensions = listExtensionFolders(root);

        for (File f : extensions) {

            source.add( new FileExtenderResource(f.getAbsolutePath() + File.separator + extensionFilename) );
            source.addAll( listFilesRecursive( new File(f.getAbsolutePath() + File.separator + "include") ) );
            source.addAll( listFilesRecursive( new File(f.getAbsolutePath() + File.separator + "src") ) );
            source.addAll( listFilesRecursive( new File(f.getAbsolutePath() + File.separator + "lib" + File.separator + platform) ) );

            String[] platformParts = platform.split("-");
            if (platformParts.length == 2 ) {
                source.addAll( listFilesRecursive( new File(f.getAbsolutePath() + File.separator + "lib" + File.separator + platformParts[1]) ) );
            }
        }
        return source;
    }

    /* Scans a directory and returns true if there are extensions available
    */
    private static boolean hasExtensions(File dir) {
        File[] files = dir.listFiles();
        if (!dir.exists()) {
            return false;
        }
        for (File f : files) {
            Matcher m = extensionPattern.matcher(f.getName());
            if (m.matches()) {
                return true;
            }

            if (f.isDirectory()) {
                if( hasExtensions(f) ) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final String extensionFilename = "ext.manifest";
    private static final Pattern extensionPattern = Pattern.compile(extensionFilename);

    private static List<File> listExtensionFolders(File dir) throws IOException {
        if (!dir.isDirectory()) {
            throw new IOException("Path is not a directory: " + dir.getAbsolutePath());
        }

        List<File> folders = new ArrayList<>();

        File[] files = dir.listFiles();
        for (File f : files) {
            Matcher m = extensionPattern.matcher(f.getName());
            if (m.matches()) {
                folders.add( dir );
                return folders;
            }
            if (f.isDirectory()) {
                folders.addAll( listExtensionFolders( f ) );
            }
        }
        return folders;
    }

    private static List<ExtenderResource> listFilesRecursive(File dir) {
        List<ExtenderResource> output = new ArrayList<>();
        if (!dir.isDirectory()) {
            return output; // the extensions doesn't have to have all folders that we look for
        }

        File[] files = dir.listFiles();
        for (File f: files) {
            if (f.isFile() ) {
                output.add( new FileExtenderResource(f) );
            } else {
                output.addAll( listFilesRecursive(f) );
            }
        }
        return output;
    }

    @Autowired
    EmbeddedWebApplicationContext server;

    @Value("${local.server.port}")
    int port;

    @Test
    public void testBuild() throws IOException, InterruptedException, ExtenderException {
        Extender extender = new Extender("x86-osx", new File("test-data/ext"), new File("test-data/sdk/a/defoldsdk"), Files.createTempDirectory("test").toString());
        File engine = extender.buildEngine();
        assertTrue(engine.isFile());
        extender.dispose();
    }

    @Test
    public void buildingRemoteShouldReturnEngine() throws IOException, ExtenderClientException {
        File cacheDir = new File("build");
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + port, cacheDir);
        List<ExtenderResource> sourceFiles = Lists.newArrayList(new FileExtenderResource("test-data/ext/ext.manifest"), new FileExtenderResource("test-data/ext/src/test_ext.cpp"), new FileExtenderResource("test-data/ext/include/test_ext.h"), new FileExtenderResource("test-data/ext/lib/x86-osx/libalib.a"));
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        String platform = "x86-osx";
        String sdkVersion = "a";
        extenderClient.build(
                platform,
                sdkVersion,
                sourceFiles,
                destination,
                log
        );

        assertTrue("Resulting engine should be of a size greater than zero.", destination.length() > 0);
        assertEquals("Log should be of size zero if successful.", 0, log.length());

        ExtenderClientCache cache = new ExtenderClientCache(cacheDir);
        assertTrue( cache.getCachedBuildFile(platform).exists() );

        FileUtils.deleteDirectory(new File("build" + File.separator + sdkVersion));
    }

    @Test
    public void testClientHasExtensions() throws IOException, InterruptedException, ExtenderException {
        assertFalse( hasExtensions(new File("test-data/testproject/a")) );
        assertTrue( hasExtensions(new File("test-data/testproject/b")) );
        assertTrue( hasExtensions(new File("test-data/testproject")) );
    }

    @Test
    public void testClientGetSource() throws IOException, InterruptedException, ExtenderException {
        File root = new File("test-data/testproject/a");
        List<ExtenderResource> files = null;

        String platform = "x86-osx";
        files = getExtensionSource(new File("test-data/testproject/a"), platform);
        assertEquals( 0, files.size() );

        files = getExtensionSource(new File("test-data/testproject/b"), platform);
        assertEquals( 5, files.size() );

        files = getExtensionSource(new File("test-data/testproject"), platform);
        assertEquals( 5, files.size() );
    }

    @Test
    public void testFilterFiles() throws IOException, InterruptedException, ExtenderException {

		String[] arr = { 
		    "a.cpp", "a.inl", "a.h", 
		    "a.cxx", "a.hpp",
		    "a.CPP", "a.hxx",
		    "a.CC", "a.CXX",
		    "a.txt", "a.o", "a.obj",
		    "a.cpp.bak", "a.cpp_",
		    "a.m", "a.bogus", "a.mm"
		};

		Collection<File> src = new ArrayList<>();
		for ( String k : arr ) {
			src.add( new File(k) );
		}

		String[] expectedNames = {
		    "a.cpp", "a.cxx",
		    "a.CPP", "a.CC", "a.CXX",
		    "a.m", "a.mm"
		};

		List<File> expected = new ArrayList<>();
		for (String k : expectedNames) {
			expected.add(new File(k));
		}

    	List<File> result = Extender.filterFiles(src, "(?i).*(.cpp|.c|.cc|.cxx|.c++|.mm|.m)");

        assertEquals( expected, result );
    }

    @Test
    public void testMergeList() throws IOException, InterruptedException, ExtenderException {
		String[] a = {  "1", "2", "2", "3", "4" };
		String[] b = {  "3", "5", "4", "5" };

        List<String> c = Extender.mergeLists(Arrays.asList(a), Arrays.asList(b));

		String[] expected = { "1", "2", "3", "4", "5" };

        assertArrayEquals(expected, c.toArray());
    }

    @Test
    public void testMergeContext() throws IOException, InterruptedException, ExtenderException {
        Map<String, Object> a = new HashMap<>();
        Map<String, Object> b = new HashMap<>();

        String[] a_frameworks = {  "a", "b", "b", "c" };
        a.put("frameworks", Arrays.asList(a_frameworks));
        String[] a_defines = {  "A", "B" };
        a.put("defines", Arrays.asList(a_defines));

        String[] b_frameworks = {  "a", "d" };
        b.put("frameworks", Arrays.asList(b_frameworks));

        Map<String, Object> result = Extender.mergeContexts(a, b);

        Map<String, Object> expected = new HashMap<>();
        String[] expected_frameworks = {  "a", "b", "c", "d" };
        expected.put("frameworks", Arrays.asList(expected_frameworks));
        String[] expected_defines = {  "A", "B" };
        expected.put("defines", Arrays.asList(expected_defines));

        assertEquals( expected, result );
    }

    @Test
    public void testListTypes() {
        List<Object> a = new ArrayList<>();
        a.add("a");
        a.add("b");
        a.add("c");
        a.add("d");
    	assertTrue( Extender.isListOfStrings(a) );

        List<Object> b = new ArrayList<>();
        b.add("a");
        b.add("b");
        b.add(1);
        b.add(2);
    	assertTrue( !Extender.isListOfStrings(b) );
    }

    @Test
    public void testCollectLibraries()
    {
       // The folder contains a library and a text file
       {
           List<String> result = Extender.collectLibraries( new File("test-data/ext/lib/x86-osx"), "lib(.+).a" );
           String[] expected = { "alib" };
           assertEquals( expected, result.toArray() );
       }
       {
           List<String> result = Extender.collectLibraries( new File("test-data/ext/lib/x86-osx"), "(.+).framework" );

           System.out.println("RESULT: " + result.toString());
           
           String[] expected = { "blib" };
           assertEquals( expected, result.toArray() );
       }
    }

    @Test
    public void testWhitelistCheckPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        patterns.add( Pattern.compile("^(apa)$") );
        patterns.add( Pattern.compile("^(bepa)$") );

        List<String> values = new ArrayList<>();

        values.clear();
        values.add("bepafant");
        assertEquals( "bepafant", ExtensionManifestValidator.whitelistCheck(patterns, values) );

        values.clear();
        values.add("apa");
        values.add("bepa");
        assertEquals( null, ExtensionManifestValidator.whitelistCheck(patterns, values) );

        values.clear();
        values.add("apa");
        values.add("bepa");
        values.add("foo");
        assertEquals( "foo", ExtensionManifestValidator.whitelistCheck(patterns, values) );
    }

    @Test
    public void testWhitelistCheckContext() throws ExtenderException, IOException {

        String[] allowedLibsTemplatesArray = new String[]{"dtrace_dyld","AccountPolicyTranslation","alias.A","alias","apr-1.0","apr-1","aprutil-1.0","aprutil-1","archive.2","archive","ATCommandStudioDynamic","auditd.0","auditd","auto","AVFAudio","blas","BSDPClient.A","BSDPClient","bsm.0","bsm","bz2.1.0.5","bz2.1.0","bz2","c\\+\\+.1","c\\+\\+","c\\+\\+abi","c","cblas","charset.1.0.0","charset.1","charset","ChineseTokenizer","clapack","cmph","com_err","compression","CoreStorage","CRFSuite","CRFSuite0.12","crypto.0.9.7","crypto.0.9.8","crypto.35","crypto","csfde","cups.2","cups","cupscgi.1","cupscgi","cupsimage.2","cupsimage","cupsmime.1","cupsmime","cupsppdc.1","cupsppdc","curl.3","curl.4","curl","curses","dbm","des425","DHCPServer.A","DHCPServer","DiagnosticMessagesClient","dl","dns_services","dtrace","ecpg.6.5","ecpg.6","ecpg","ecpg_compat.3.5","ecpg_compat.3","ecpg_compat","edit.2","edit.3.0","edit.3","edit","energytrace","expat.1","expat","exslt.0","exslt","extension","f77lapack","ffi","form.5.4","form","Fosl_dynamic","gcc_s.1","gcc_s.10.4","gcc_s.10.5","germantok","gmalloc","gssapi_krb5","heimdal-asn1","hunspell-1.2.0.0.0","hunspell-1.2.0","hunspell-1.2","IASAuthReboot","IASUnifiedProgress","iconv.2.4.0","iconv.2","iconv","icucore.A","icucore","info","iodbc.2.1.18","iodbc.2","iodbc","iodbcinst.2.1.18","iodbcinst.2","iodbcinst","ipconfig","ipsec.A","ipsec","k5crypto","krb4","krb5","krb524","krb5support","ktrace","langid","lapack","lber","ldap","ldap_r","lzma.5","lzma","m","marisa","Match.1","Match","mecab.1.0.0","mecab","mecabra","menu.5.4","menu","mx.A","mx","ncurses.5.4","ncurses.5","ncurses","netsnmp.15.1.2","netsnmp.15","netsnmp.25","netsnmp.5.2.1","netsnmp.5","netsnmp","netsnmpagent.25","netsnmpagent","netsnmphelpers.25","netsnmphelpers","netsnmpmibs.25","netsnmpmibs","netsnmptrapd.25","netsnmptrapd","network","objc.A","objc","odfde","odmodule","OpenScriptingUtil","pam.1","pam.2","pam","panel.5.4","panel","pcap.A","pcap","pcre.0","pcre","pcreposix.0","pcreposix","pgtypes.3.4","pgtypes.3","pgtypes","pmenergy","pmsample","poll","pq.5.6","pq.5","pq","prequelite","proc","pthread","python","python2.6","python2.7","QMIParserDynamic","quit","readline","resolv.9","resolv","rpcsvc","ruby.2.0.0","ruby.2.0","ruby","sandbox.1","sandbox","sasl2.2.0.1","sasl2.2.0.15","sasl2.2.0.21","sasl2.2.0.22","sasl2.2","sasl2","ScreenReader","spindump","sqlite3.0","sqlite3","ssl.0.9.7","ssl.0.9.8","ssl.35","ssl","stdc\\+\\+.6.0.9","stdc\\+\\+.6","stdc\\+\\+","sysmon","System.B","System.B_debug","System","System_debug","systemstats","tcl","tcl8.5","TelephonyUtilDynamic","termcap","ThaiTokenizer","tidy.A","tidy","tk","tk8.5","tls.6","tls","UniversalAccess","util","util1.0","xar.1","xar","xcselect","xml2.2","xml2","Xplugin.1","Xplugin","XSEvent","xslt.1","xslt","z.1.1.3","z.1.2.5","z.1","z","ssh-keychain","dispatch","system_pthread","cache","commonCrypto","compiler_rt","copyfile","corecrypto","dispatch","dyld","keymgr","kxld","launch","macho","mathCommon.A","mathCommon","quarantine","removefile","system_asl","system_blocks","system_c","system_configuration","system_coreservices","system_coretls","system_dnssd","system_info","system_kernel","system_m","system_malloc","system_network","system_networkextension","system_notify","system_platform","system_pthread","system_sandbox","system_secinit","system_trace","unc","unwind","xpc","AGL","AVFoundation","AVKit","Accelerate","Accounts","AddressBook","AppKit","AppKitScripting","AppleScriptKit","AppleScriptObjC","ApplicationServices","AudioToolbox","AudioUnit","AudioVideoBridging","Automator","CFNetwork","CalendarStore","Carbon","CloudKit","Cocoa","Collaboration","Contacts","ContactsUI","CoreAudio","CoreAudioKit","CoreBluetooth","CoreData","CoreFoundation","CoreGraphics","CoreImage","CoreLocation","CoreMIDI","CoreMIDIServer","CoreMedia","CoreMediaIO","CoreServices","CoreTelephony","CoreText","CoreVideo","CoreWLAN","CryptoTokenKit","DVComponentGlue","DVDPlayback","DirectoryService","DiscRecording","DiscRecordingUI","DiskArbitration","DrawSprocket","EventKit","ExceptionHandling","FWAUserLib","FinderSync","ForceFeedback","Foundation","GLKit","GLUT","GSS","GameController","GameKit","GameplayKit","Hypervisor","ICADevices","IMServicePlugIn","IOBluetooth","IOBluetoothUI","IOKit","IOSurface","ImageCaptureCore","ImageIO","InputMethodKit","InstallerPlugins","InstantMessage","JavaFrameEmbedding","JavaScriptCore","JavaVM","Kerberos","Kernel","LDAP","LatentSemanticMapping","LocalAuthentication","MapKit","MediaAccessibility","MediaLibrary","MediaToolbox","Message","Metal","MetalKit","ModelIO","MultipeerConnectivity","NetFS","NetworkExtension","NotificationCenter","OSAKit","OpenAL","OpenCL","OpenDirectory","OpenGL","PCSC","Photos","PhotosUI","PreferencePanes","PubSub","Python","QTKit","Quartz","QuartzCore","QuickLook","QuickTime","Ruby","SceneKit","ScreenSaver","Scripting","ScriptingBridge","Security","SecurityFoundation","SecurityInterface","ServiceManagement","Social","SpriteKit","StoreKit","SyncServices","System","SystemConfiguration","TWAIN","Tcl","Tk","VideoDecodeAcceleration","VideoToolbox","WebKit","vecLib","vmnet"};
        List<String> allowedLibsTemplates = Arrays.asList(allowedLibsTemplatesArray);

        String[] allowedFlagsTemplatesArray = new String[]{"-ObjC","-ObjC++","-Wa,{{comma_separated_arg}}","-W{{warning}}","-ansi","--ansi","-std-default={{arg}}","-stdlib=(libstdc\\+\\+|libc\\+\\+)","-w","-std=(c89|c99|c\\+\\+0x|c\\+\\+11|c\\+\\+14|c\\+\\+17)","-Wp,{{comma_separated_arg}}","-W{{warning}}","--extra-warnings","--warn-{{warning}}","--warn-={{warning}}","-ferror-limit={{number}}","-O([0-4]?|fast|s|z)"};
        List<String> allowedFlagsTemplates = Arrays.asList(allowedFlagsTemplatesArray);

        InputStream configFileInputStream = Files.newInputStream(new File("test-data/sdk/a/defoldsdk/extender/build.yml").toPath());
        Configuration config = new Yaml().loadAs(configFileInputStream, Configuration.class);
        ExtensionManifestValidator validator = new ExtensionManifestValidator(config.whitelist, allowedFlagsTemplates, allowedLibsTemplates);

        List<String> stringValues = new ArrayList<>();
        Map<String, Object> context = new HashMap<>();

        // LIBS

        stringValues.clear();
        context.clear();
        stringValues.add("AVFAudio");
        stringValues.add("QuickTime");
        context.put("libs", stringValues);

        validator.validate("test_extension", context);


        stringValues.clear();
        context.clear();
        stringValues.add("c++");
        context.put("libs", stringValues);

        validator.validate("test_extension", context);


        stringValues.clear();
        context.clear();
        stringValues.add("apa");
        stringValues.add("foo");
        context.put("libs", stringValues);

        boolean thrown = false;
        try {
            thrown = false;
            validator.validate("test_extension", context);
        } catch (ExtenderException e) {
            thrown = true;
        }
        assertTrue(thrown);


        // FLAGS

        stringValues.clear();
        context.clear();
        stringValues.add("-O");
        stringValues.add("-Weverything");
        context.put("flags", stringValues);

        validator.validate("test_extension", context);



        stringValues.clear();
        context.clear();
        stringValues.add("-std=c++0x");
        stringValues.add("-std=c++11");
        stringValues.add("-std=c++14");
        context.put("flags", stringValues);

        validator.validate("test_extension", context);



        stringValues.clear();
        context.clear();
        stringValues.add("-O");
        stringValues.add("-Weverything; rm -rf");
        context.put("flags", stringValues);

        try {
            thrown = false;
            validator.validate("test_extension", context);
        } catch (ExtenderException e) {
            if (e.toString().contains("rm -rf")) {
                thrown = true;
            } else {
                throw e;
            }
        }
        assertTrue(thrown);
    }

    // Extender client

    private void writeToFile(String path, String msg) throws IOException {
        File f = new File(path);
        FileWriter fwr = new FileWriter(f);
        fwr.write(msg);
        fwr.flush();
        fwr.close();
        f.setLastModified(Instant.now().toEpochMilli() + 23);
    }

    @Test
    public void testClientCacheHash() throws IOException, InterruptedException, ExtenderClientException {
        writeToFile("build/a", "a");
        writeToFile("build/b", "a");
        writeToFile("build/c", "b");

        ExtenderClientCache cache = new ExtenderClientCache(new File("."));

        {
            File file1 = new File("build/a");
            File file2 = new File("build/b");
            File file3 = new File("build/c");
            FileExtenderResource file1Res = new FileExtenderResource(file1);
            FileExtenderResource file2Res = new FileExtenderResource(file2);
            FileExtenderResource file3Res = new FileExtenderResource(file3);
            assertEquals(cache.getHash(file1Res), cache.getHash(file2Res));
            assertNotEquals(cache.getHash(file1Res), cache.getHash(file3Res));
        }

        Thread.sleep(1000);

        writeToFile("build/b", "b");

        {
            File file1 = new File("build/a");
            File file2 = new File("build/b");
            FileExtenderResource file1Res = new FileExtenderResource(file1);
            FileExtenderResource file2Res = new FileExtenderResource(file2);

            assertNotEquals(cache.getHash(file1Res), cache.getHash(file2Res));
        }

        FileUtils.deleteQuietly(new File("build/a"));
        FileUtils.deleteQuietly(new File("build/b"));
        FileUtils.deleteQuietly(new File("build/c"));
    }

    @Test
    public void testClientCacheSignatureHash() throws IOException, ExtenderClientException {
        File a = new File("build/a");
        File b = new File("build/b");
        FileExtenderResource aRes = new FileExtenderResource(a);
        FileExtenderResource bRes = new FileExtenderResource(b);

        writeToFile("build/a", "a");
        writeToFile("build/b", "b");

        List<ExtenderResource> files1 = new ArrayList<>();
        files1.add(aRes);
        files1.add(bRes);


        List<ExtenderResource> files2 = new ArrayList<>();
        files2.add(bRes);
        files2.add(aRes);

        String platform = "osx";
        String sdkVersion = "abc456";
        ExtenderClientCache cache = new ExtenderClientCache(new File("."));

        assertEquals(cache.getHash(files1), cache.getHash(files2));
        assertEquals(cache.calcKey(platform, sdkVersion, files1), cache.calcKey(platform, sdkVersion, files2));

        files2.add(aRes);

        assertNotEquals(cache.getHash(files1), cache.getHash(files2));
        assertNotEquals(cache.calcKey(platform, sdkVersion, files1), cache.calcKey(platform, sdkVersion, files2));

        FileUtils.deleteQuietly(new File("build/a"));
        FileUtils.deleteQuietly(new File("build/b"));
    }

    @Test
    public void testClientCacheValidBuild() throws IOException, InterruptedException, ExtenderClientException {
        File a = new File("build/a");
        File b = new File("build/b");
        File c = new File("build/c");
        FileExtenderResource aRes = new FileExtenderResource(a);
        FileExtenderResource bRes = new FileExtenderResource(b);
        FileExtenderResource cRes = new FileExtenderResource(c);

        a.deleteOnExit();
        b.deleteOnExit();
        c.deleteOnExit();

        writeToFile("build/a", "a");
        writeToFile("build/b", "b");
        writeToFile("build/c", "c");

        List<ExtenderResource> files = new ArrayList<>();
        files.add(aRes);
        files.add(bRes);

        String platform = "osx";
        String sdkVersion = "abc456";
        ExtenderClientCache cache = new ExtenderClientCache(new File("."));

        if (cache.getCacheFile().exists()) {
            cache.getCacheFile().delete();
        }

        String key = null;
        // Is doesn't exist yet, so false
        key = cache.calcKey(platform, sdkVersion, files);
        assertEquals( false, cache.isCached(platform, key) );

        File build = cache.getCachedBuildFile(platform);
        build.deleteOnExit();

        writeToFile(build.getAbsolutePath(), (new Date()).toString());
        cache.put(platform, key, build);

        // It should exist now, so true
        assertEquals( true, cache.isCached(platform, key) );

        // Changing a source file should invalidate the file
        Thread.sleep(1000);
        writeToFile("build/b", "bb");
        key = cache.calcKey(platform, sdkVersion, files);

        assertEquals( false, cache.isCached(platform, key) );

        // If we update the build, is should be cached
        cache.put(platform, key, build);
        assertEquals( true, cache.isCached(platform, key) );

        // Add a "new" file to the list, but let it have an old timestamp
        files.add(cRes);
        key = cache.calcKey(platform, sdkVersion, files);

        assertEquals( false, cache.isCached(platform, key) );

        // If we update the build, is should be cached
        cache.put(platform, key, build);
        assertEquals( true, cache.isCached(platform, key) );

        // Remove one file
        files.remove(0);
        key = cache.calcKey(platform, sdkVersion, files);

        assertEquals( false, cache.isCached(platform, key) );

        // If we update the build, is should be cached
        cache.put(platform, key, build);
        assertEquals( true, cache.isCached(platform, key) );
    }

    public static String calcChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] data = Files.readAllBytes(file.toPath());
        md.update(data);
        byte[] digest = md.digest();
        return (new HexBinaryAdapter()).marshal(digest);
    }

    @Test
    public void testClientCachePersistence() throws IOException, InterruptedException, ExtenderClientException, NoSuchAlgorithmException {
        File a = new File("build/a");
        FileExtenderResource aRes = new FileExtenderResource(a);
        a.deleteOnExit();
        writeToFile("build/a", "a");

        List<ExtenderResource> files = new ArrayList<>();
        files.add(aRes);

        String platform = "osx";
        String sdkVersion = "abc456";
        File cacheDir = new File(".");


        String key = null;
        {
            ExtenderClientCache cache = new ExtenderClientCache(cacheDir);
            key = cache.calcKey(platform, sdkVersion, files);

            if (cache.getCacheFile().exists()) {
                cache.getCacheFile().delete();
            }
            assertFalse( cache.getCacheFile().exists() );
        }

        // Start with an empty cache
        String checksum = null;
        {
            ExtenderClientCache cache = new ExtenderClientCache(cacheDir);

            assertEquals( false, cache.isCached(platform, key) );

            // Write the build, and update the cache
            File build = File.createTempFile("test", "build");
            build.deleteOnExit();
            writeToFile(build.getAbsolutePath(), (new Date()).toString());
            cache.put(platform, key, build);

            checksum = calcChecksum(build);
        }

        // Now, lets create another cache, and check that we get a cached version
        {
            ExtenderClientCache cache = new ExtenderClientCache(cacheDir);

            assertEquals( true, cache.isCached(platform, key) );

            File build = File.createTempFile("test2", "build");
            cache.get(platform, key, build);

            String checksum2 = calcChecksum(build);

            assertEquals( checksum, checksum2 );
        }
    }

}
