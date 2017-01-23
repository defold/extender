package com.defold.extender;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.ExtenderClientCache;
import com.defold.extender.client.ExtenderClientException;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment= SpringBootTest.WebEnvironment.RANDOM_PORT, value = "extender.defoldSdkPath = test-data/sdk")
public class ExtenderTest {

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
        List<File> sourceFiles = Lists.newArrayList(new File("test-data/ext/ext.manifest"), new File("test-data/ext/src/test_ext.cpp"), new File("test-data/ext/include/test_ext.h"), new File("test-data/ext/lib/x86-osx/libalib.a"));
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        String platform = "x86-osx";
        String sdkVersion = "a";
        File cacheDir = new File("build");
        extenderClient.build(
                platform,
                sdkVersion,
                new File(""),
                sourceFiles,
                cacheDir,
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
        assertFalse( ExtenderClient.hasExtensions(new File("test-data/testproject/a")) );
        assertTrue( ExtenderClient.hasExtensions(new File("test-data/testproject/b")) );
        assertTrue( ExtenderClient.hasExtensions(new File("test-data/testproject")) );
    }

    @Test
    public void testClientGetSource() throws IOException, InterruptedException, ExtenderException {
        File root = new File("test-data/testproject/a");
        List<File> files = null;

        String platform = "x86-osx";
        files = ExtenderClient.getExtensionSource(new File("test-data/testproject/a"), platform);
        assertEquals( 0, files.size() );

        files = ExtenderClient.getExtensionSource(new File("test-data/testproject/b"), platform);
        assertEquals( 5, files.size() );

        files = ExtenderClient.getExtensionSource(new File("test-data/testproject"), platform);
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
    public void testClientCacheHash() throws IOException, InterruptedException {
        FileWriter fwr1 = new FileWriter(new File("build/a"));
        FileWriter fwr2 = new FileWriter(new File("build/b"));
        FileWriter fwr3 = new FileWriter(new File("build/c"));
        fwr1.write("a");
        fwr2.write("a");
        fwr3.write("b");
        fwr1.close();
        fwr2.close();
        fwr3.close();

        ExtenderClientCache cache = new ExtenderClientCache(new File("."));

        {
            File file1 = new File("build/a");
            File file2 = new File("build/b");
            File file3 = new File("build/c");
            assertEquals(cache.getHash(file1), cache.getHash(file2));
            assertNotEquals(cache.getHash(file1), cache.getHash(file3));
        }

        Thread.sleep(1000);

        fwr2 = new FileWriter(new File("build/b"));
        fwr2.write("b");
        fwr2.flush();
        fwr2.close();

        {
            File file1 = new File("build/a");
            File file2 = new File("build/b");

            assertNotEquals(cache.getHash(file1), cache.getHash(file2));
        }

        FileUtils.deleteQuietly(new File("build/a"));
        FileUtils.deleteQuietly(new File("build/b"));
        FileUtils.deleteQuietly(new File("build/c"));
    }

    @Test
    public void testClientCacheSignatureHash() throws IOException {
        File a = new File("build/a");
        File b = new File("build/b");
        FileWriter fwr1 = new FileWriter(a);
        FileWriter fwr2 = new FileWriter(b);
        fwr1.write("a");
        fwr2.write("b");
        fwr1.flush();
        fwr2.flush();
        fwr1.close();
        fwr2.close();

        List<File> files1 = new ArrayList<>();
        files1.add(a);
        files1.add(b);


        List<File> files2 = new ArrayList<>();
        files2.add(b);
        files2.add(a);

        ExtenderClientCache cache = new ExtenderClientCache(new File("."));

        assertEquals(cache.getHash(files1), cache.getHash(files2));

        files2.add(a);

        assertNotEquals(cache.getHash(files1), cache.getHash(files2));

        FileUtils.deleteQuietly(new File("build/a"));
        FileUtils.deleteQuietly(new File("build/b"));
    }
}
