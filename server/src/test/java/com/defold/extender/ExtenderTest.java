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
import java.time.Instant;
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
        File cacheDir = new File("build");
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + port, cacheDir);
        List<File> sourceFiles = Lists.newArrayList(new File("test-data/ext/ext.manifest"), new File("test-data/ext/src/test_ext.cpp"), new File("test-data/ext/include/test_ext.h"), new File("test-data/ext/lib/x86-osx/libalib.a"));
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        String platform = "x86-osx";
        String sdkVersion = "a";
        extenderClient.build(
                platform,
                sdkVersion,
                new File(""),
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

    private void writeToFile(String path, String msg) throws IOException {
        File f = new File(path);
        FileWriter fwr = new FileWriter(f);
        fwr.write(msg);
        fwr.flush();
        fwr.close();
        f.setLastModified(Instant.now().toEpochMilli() + 23);
    }

    @Test
    public void testClientCacheHash() throws IOException, InterruptedException {
        writeToFile("build/a", "a");
        writeToFile("build/b", "a");
        writeToFile("build/c", "b");

        ExtenderClientCache cache = new ExtenderClientCache(new File("."));

        {
            File file1 = new File("build/a");
            File file2 = new File("build/b");
            File file3 = new File("build/c");
            assertEquals(cache.getHash(file1), cache.getHash(file2));
            assertNotEquals(cache.getHash(file1), cache.getHash(file3));
        }

        Thread.sleep(1000);

        writeToFile("build/b", "b");

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

        writeToFile("build/a", "a");
        writeToFile("build/b", "b");

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

    @Test
    public void testClientCacheValidBuild() throws IOException, InterruptedException {
        File a = new File("build/a");
        File b = new File("build/b");
        File c = new File("build/c");
        a.deleteOnExit();
        b.deleteOnExit();
        c.deleteOnExit();

        writeToFile("build/a", "a");
        writeToFile("build/b", "b");
        writeToFile("build/c", "c");

        List<File> files = new ArrayList<>();
        files.add(a);
        files.add(b);

        String platform = "osx";
        String sdkVersion = "abc456";
        ExtenderClientCache cache = new ExtenderClientCache(new File("."));

        // Is doesn't exist yet, so false
        assertEquals( null, cache.isCachedBuildValid(platform, sdkVersion, files) );

        File build = cache.getCachedBuildFile(platform);
        build.deleteOnExit();
        File parentDir = build.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }

        writeToFile(build.getAbsolutePath(), (new Date()).toString());
        cache.storeCachedBuild(platform, sdkVersion, files);

        // It should exist now, so true
        assertEquals( build, cache.isCachedBuildValid(platform, sdkVersion, files) );

        // Changing a source file should invalidate the file
        Thread.sleep(1000);
        writeToFile("build/b", "bb");
        assertEquals( null, cache.isCachedBuildValid(platform, sdkVersion, files) );

        // If we update the build, is should be cached
        cache.storeCachedBuild(platform, sdkVersion, files);
        assertEquals( build, cache.isCachedBuildValid(platform, sdkVersion, files) );

        // Add a "new" file to the list, but let it have an old timestamp
        files.add(c);

        assertEquals( null, cache.isCachedBuildValid(platform, sdkVersion, files) );

        // If we update the build, is should be cached
        cache.storeCachedBuild(platform, sdkVersion, files);
        assertEquals( build, cache.isCachedBuildValid(platform, sdkVersion, files) );

        // Remove one file
        files.remove(0);

        assertEquals( null, cache.isCachedBuildValid(platform, sdkVersion, files) );

        // If we update the build, is should be cached
        cache.storeCachedBuild(platform, sdkVersion, files);
        assertEquals( build, cache.isCachedBuildValid(platform, sdkVersion, files) );
    }

    @Test
    public void testClientCachePersistence() throws IOException, InterruptedException {
        File a = new File("build/a");
        a.deleteOnExit();
        writeToFile("build/a", "a");

        List<File> files = new ArrayList<>();
        files.add(a);

        String platform = "osx";
        String sdkVersion = "abc456";
        File cacheDir = new File(".");

        // First, get the file, and create the directory
        File build = null;
        {
            ExtenderClientCache cache = new ExtenderClientCache(cacheDir);

            if (cache.getCacheFile().exists()) {
                cache.getCacheFile().delete();
            }
            assertFalse( cache.getCacheFile().exists() );

            build = cache.getCachedBuildFile(platform);
            build.deleteOnExit();

            File parentDir = build.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }
        }

        // Start with an empty cache
        {
            ExtenderClientCache cache = new ExtenderClientCache(cacheDir);

            assertEquals( null, cache.isCachedBuildValid(platform, sdkVersion, files) );

            // Write the build, and update the cache
            writeToFile(build.getAbsolutePath(), (new Date()).toString());
            cache.storeCachedBuild(platform, sdkVersion, files);
        }

        // Now, lets create another cache, and check that we get a cached version
        {
            ExtenderClientCache cache = new ExtenderClientCache(cacheDir);

            assertEquals( build, cache.isCachedBuildValid(platform, sdkVersion, files) );
        }
    }

}
