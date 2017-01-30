package com.defold.extender;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.ExtenderClientCache;
import com.defold.extender.client.ExtenderClientException;
import com.defold.extender.client.IExtenderResource;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment= SpringBootTest.WebEnvironment.RANDOM_PORT, value = "extender.defoldSdkPath = test-data/sdk")
public class ExtenderTest {

    private static class FileExtenderResource implements IExtenderResource {

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

    private static List<IExtenderResource> getExtensionSource(File root, String platform) throws IOException {
        List<IExtenderResource> source = new ArrayList<>();
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

    private static List<IExtenderResource> listFilesRecursive(File dir) {
        List<IExtenderResource> output = new ArrayList<>();
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
        Extender extender = new Extender("x86-osx", new File("test-data/ext"), new File("test-data/sdk/a/defoldsdk"));
        File engine = extender.buildEngine();
        assertTrue(engine.isFile());
        extender.dispose();
    }

    @Test
    public void buildingRemoteShouldReturnEngine() throws IOException, ExtenderClientException {
        File cacheDir = new File("build");
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + port, cacheDir);
        List<IExtenderResource> sourceFiles = Lists.newArrayList(new FileExtenderResource("test-data/ext/ext.manifest"), new FileExtenderResource("test-data/ext/src/test_ext.cpp"), new FileExtenderResource("test-data/ext/include/test_ext.h"), new FileExtenderResource("test-data/ext/lib/x86-osx/libalib.a"));
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
        List<IExtenderResource> files = null;

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

        List<IExtenderResource> files1 = new ArrayList<>();
        files1.add(aRes);
        files1.add(bRes);


        List<IExtenderResource> files2 = new ArrayList<>();
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

        List<IExtenderResource> files = new ArrayList<>();
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

        List<IExtenderResource> files = new ArrayList<>();
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
