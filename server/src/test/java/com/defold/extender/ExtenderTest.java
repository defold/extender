package com.defold.extender;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.ExtenderClientException;
import com.defold.extender.client.IExtenderResource;
import com.google.common.collect.Lists;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment= SpringBootTest.WebEnvironment.RANDOM_PORT, value = "extender.defoldSdkPath = test-data/sdk")
public class ExtenderTest {

    private static class FileExtenderResource implements IExtenderResource {

        private File file;
        private String filePath;
        private byte[] content;
        FileExtenderResource(String filePath) {
            this.filePath = filePath;
            this.file = new File(filePath);
        }

        FileExtenderResource(File file) {
            this.file = file;
        }

        @Override
        public byte[] sha1() throws IOException {
            return new byte[0];
        }

        @Override
        public String getAbsPath() {
            return filePath;
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
            return 0;
        }
    }

    private static List<IExtenderResource> getExtensionSource(File root, String platform) throws IOException {
        List<IExtenderResource> source = new ArrayList<>();
        List<File> extensions = listExtensionFolders(root);

        for (File f : extensions) {

            source.add( new FileExtenderResource(f.getAbsolutePath() + File.separator + ExtenderClient.extensionFilename) );
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
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + port);
        List<IExtenderResource> sourceFiles = Lists.newArrayList(new FileExtenderResource("test-data/ext/ext.manifest"), new FileExtenderResource("test-data/ext/src/test_ext.cpp"), new FileExtenderResource("test-data/ext/include/test_ext.h"), new FileExtenderResource("test-data/ext/lib/x86-osx/libalib.a"));
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        extenderClient.build(
                "x86-osx",
                "a",
                sourceFiles,
                destination,
                log
        );

        assertTrue("Resulting engine should be of a size greater than zero.", destination.length() > 0);
        assertEquals("Log should be of size zero if successful.", 0, log.length());
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
}
