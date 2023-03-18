package com.defold.extender;

import com.defold.extender.ExtenderUtil;

import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.*;

public class ExtenderTest {

    File uploadDirectory = null;

    @Test
    public void testExtender() throws IOException, InterruptedException, ExtenderException {
        File jobDir = new File("/tmp/tmpJob");
        jobDir.mkdirs();
        jobDir.deleteOnExit();
        File uploadDir = new File(jobDir, "upload");
        uploadDir.mkdirs();
        File buildDir = new File(jobDir, "build");
        buildDir.mkdirs();
        File sdk = new File("test-data/sdk/a/defoldsdk");


        Map<String, String> env = new HashMap<>();

        // TODO: Read these from the Dockerfile itself
        env.put("PLATFORMSDK_DIR", "/opt/platformsdk");
        env.put("MANIFEST_MERGE_TOOL", "/opt/local/bin/manifestmergetool.jar");
        env.put("XCODE_14_VERSION", "14.2");
        env.put("IOS_16_VERSION", "16.2");
        env.put("LIB_TAPI_1_6_PATH", "/usr/local/tapi1.6/lib");
        env.put("MACOS_13_VERSION", "13.1");
        env.put("XCODE_14_CLANG_VERSION", "14.0.0");
        env.put("SWIFT_5_5_VERSION", "5.5");
        env.put("SYSROOT", "/opt/platformsdk/MacOSX13.1.sdk");
        env.put("LD_LIBRARY_PATH", "/usr/local/tapi1.6/lib");

        Extender extender = new Extender("x86_64-osx", sdk, jobDir, uploadDir, buildDir, env);

        uploadDir.delete();
        assertTrue(true);
    }

    public static MultipartHttpServletRequest createMultipartHttpRequest(List<MockMultipartFile> files) throws IOException {
        MockMultipartHttpServletRequestBuilder builder = MockMvcRequestBuilders.multipart("/testurl"); // The url isn't used here
        for (MockMultipartFile file : files)
        {
            builder.file(file);
        }
        return (MultipartHttpServletRequest)builder.buildRequest(new MockServletContext());
    }

    @Before
    public void setUp() throws IOException {
        this.uploadDirectory = Files.createTempDirectory("upload").toFile();
        this.uploadDirectory.deleteOnExit();
    }

    @After
    public void tearDown() throws IOException {
        if (this.uploadDirectory != null) {
            FileUtils.deleteDirectory(this.uploadDirectory);
            this.uploadDirectory = null;
        }
    }

    @Test
    public void testReceiveFiles() throws IOException, FileUploadException, ExtenderException {
        MultipartHttpServletRequest request;
        String filename;
        String expectedContent;

        // Should be fine
        filename = "include/test.h";
        expectedContent = "//ABcdEFgh";
        String dsStoreFilename = "bundle/.DS_Store";

        List<MockMultipartFile> files = new ArrayList<>();
        files.add(new MockMultipartFile(filename, expectedContent.getBytes()));
        files.add(new MockMultipartFile(dsStoreFilename, "shouldn't be received".getBytes()));
        request = createMultipartHttpRequest(files);
        {
            ExtenderController.receiveUpload(request, uploadDirectory);
            File file = new File(uploadDirectory.getAbsolutePath() + "/" + filename);
            file.deleteOnExit();
            assertTrue(file.exists());
            String fileContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertTrue(expectedContent.equals(fileContent));

            File ds_store = new File(uploadDirectory.getAbsolutePath() + "/" + dsStoreFilename);
            assertFalse(ds_store.exists());
        }

        // Mustn't upload files outside of the folder!
        filename = "../include/test.h";
        expectedContent = "//invalidfile";

        files = new ArrayList<>();
        files.add(new MockMultipartFile(filename, expectedContent.getBytes()));
        request = createMultipartHttpRequest(files);
        {
            boolean thrown = false;
            try {
                ExtenderController.receiveUpload(request, uploadDirectory);
            } catch (ExtenderException e) {
                thrown = true;
            }
            assertTrue(thrown);
            File file = new File(uploadDirectory.getAbsolutePath() + "/" + filename);
            assertFalse(file.exists());
        }

        // Should be fine (Windows back slashes)
        filename = "src/foo/bar/test.cpp";
        expectedContent = "//ABcdEFgh";

        files = new ArrayList<>();
        files.add(new MockMultipartFile("src\\foo\\bar\\test.cpp", expectedContent.getBytes()));
        request = createMultipartHttpRequest(files);
        {
            ExtenderController.receiveUpload(request, uploadDirectory);
            File file = new File(uploadDirectory.getAbsolutePath() + "/" + filename);
            file.deleteOnExit();
            assertTrue(file.exists());
            String fileContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertTrue(expectedContent.equals(fileContent));
        }
    }

    static private boolean testPath(String path) {
        boolean thrown = false;
        try {
            ExtenderController.validateFilename(path);
        } catch (ExtenderException e) {
            thrown = true;
        }
        return !thrown;
    }

    @Test
    public void testValidateFilenames() {
        // Should be fine
        assertTrue(testPath("include/test.h"));
        assertTrue(testPath("include/test+framework.h"));
        assertTrue(testPath("src/test.c++"));
        assertTrue(testPath("src/icon@2x.png"));
        // Should throw error
        assertFalse(testPath("+foobar.h"));
        assertFalse(testPath("include/foo;echo foo;.h")); // trying to sneak in an echo command
        assertFalse(testPath("../../etc/passwd")); // trying to sneak in a new system file
    }

    @Test
    public void testFilterFiles() {

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
        for (String k : arr) {
            src.add(new File(k));
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

        List<File> result = ExtenderUtil.filterFiles(src, "(?i).*(.cpp|.c|.cc|.cxx|.c++|.mm|.m)");

        assertEquals(expected, result);
    }


    @Test
    public void testListTypes() {
        List<Object> a = new ArrayList<>();
        a.add("a");
        a.add("b");
        a.add("c");
        a.add("d");
        assertTrue(ExtenderUtil.isListOfStrings(a));

        List<Object> b = new ArrayList<>();
        b.add("a");
        b.add("b");
        b.add(1);
        b.add(2);
        assertTrue(!ExtenderUtil.isListOfStrings(b));
    }

    @Test
    public void testCollectLibraries() {
        // The folder contains a library and a text file
        {
            List<String> result = ExtenderUtil.collectFilesByName(new File("test-data/ext/lib/x86_64-osx"), "lib(.+).a");
            String[] expected = {"alib"};
            assertArrayEquals(expected, result.toArray());
        }
        {
            List<String> result = ExtenderUtil.collectFilesByName(new File("test-data/ext/lib/x86_64-osx"), Extender.FRAMEWORK_RE);
            String[] expected = {"blib"};
            assertArrayEquals(expected, result.toArray());
        }
    }

    @Test
    public void testCollectJars() {
        List<String> paths = ExtenderUtil.collectFilesByPath(new File("test-data/ext/lib/armv7-android"), Extender.JAR_RE);
        assertEquals(4, paths.size());

        String[] endings = {"test-data/ext/lib/armv7-android/Dummy.jar", "test-data/ext/lib/armv7-android/JarDep.jar",
                            "test-data/ext/lib/armv7-android/VeryLarge1.jar", "test-data/ext/lib/armv7-android/VeryLarge2.jar"};

        for (String p : endings) {
            boolean exists = false;
            for (String path : paths) {
                if (path.endsWith(p)) {
                    exists = true;
                    break;
                }
            }
            assertTrue(exists);
        }
    }

    @Test
    public void testCollectJsFiles() {
        List<String> result = ExtenderUtil.collectFilesByPath(new File("test-data/ext/lib/js-web"), Extender.JS_RE);
        assertEquals(1, result.size());
        assertTrue(result.get(0).endsWith("test-data/ext/lib/js-web/library_dummy.js"));
    }

    @Test
    public void testExcludeItems() throws IOException, InterruptedException, ExtenderException {

        File root = new File("test-data");
        File appManifestFile = new File("test-data/extendertest.appmanifest");

        AppManifestConfiguration appManifest = Extender.loadYaml(root, appManifestFile, AppManifestConfiguration.class);

        assertTrue(appManifest != null);

        // Make sure it handles platforms
        {
            List<String> items = ExtenderUtil.getAppManifestItems(appManifest, "x86_64-osx", "excludeSymbols");
            assertTrue( items.contains("SymbolA") );
            assertTrue( items.contains("SymbolB") );
            assertFalse( items.contains("SymbolC") );
        }

        {
            List<String> includePatterns = ExtenderUtil.getAppManifestItems(appManifest, "x86_64-osx", "includeSymbols");
            List<String> excludePatterns = ExtenderUtil.getAppManifestItems(appManifest, "x86_64-osx", "excludeSymbols");
            List<String> allItems = new ArrayList<>();
            allItems.add("SymbolA");
            allItems.add("SymbolB");
            allItems.add("SymbolC");

            List<String> items = ExtenderUtil.pruneItems(allItems, includePatterns, excludePatterns);
            assertEquals( 1, items.size() );
            assertTrue( items.contains("SymbolC") );
        }

        {
            List<String> includePatterns = new ArrayList<>();;
            List<String> excludePatterns = new ArrayList<>();
            excludePatterns.add(".*/google-play-services.jar");

            List<String> allItems = new ArrayList<>();
            allItems.add("{{dynamo_home}}/ext/share/java/facebooksdk.jar");
            allItems.add("{{dynamo_home}}/ext/share/java/google-play-services.jar");

            List<String> items = ExtenderUtil.pruneItems(allItems, includePatterns, excludePatterns);
            assertEquals( 1, items.size() );
            assertTrue( items.contains("{{dynamo_home}}/ext/share/java/facebooksdk.jar") );
        }

        {
            List<String> includePatterns = new ArrayList<>();;
            List<String> excludePatterns = new ArrayList<>();
            excludePatterns.add("(.*)google-play-services.jar");

            List<String> allItems = new ArrayList<>();
            allItems.add("{{dynamo_home}}/ext/share/java/facebooksdk.jar");
            allItems.add("{{dynamo_home}}/ext/share/java/google-play-services.jar");

            List<String> items = ExtenderUtil.pruneItems(allItems, includePatterns, excludePatterns);
            assertEquals( 1, items.size() );
            assertTrue( items.contains("{{dynamo_home}}/ext/share/java/facebooksdk.jar") );
        }

        {
            List<String> includePatterns = new ArrayList<>();;
            List<String> excludePatterns = new ArrayList<>();
            excludePatterns.add("(.*).jar");                // removes all jars
            includePatterns.add("(.*)facebook(.*).jar");    // keeps the facebook jars

            List<String> allItems = new ArrayList<>();
            allItems.add("{{dynamo_home}}/ext/share/java/facebooksdk.jar");
            allItems.add("{{dynamo_home}}/ext/share/java/google-play-services.jar");

            List<String> items = ExtenderUtil.pruneItems(allItems, includePatterns, excludePatterns);
            assertEquals( 1, items.size() );
            assertTrue( items.contains("{{dynamo_home}}/ext/share/java/facebooksdk.jar") );
        }
    }

    @Test
    public void testAppManifestContext() throws IOException, ExtenderException {

        File root = new File("test-data");
        File appManifestFile = new File("test-data/extendertest.appmanifest");

        AppManifestConfiguration appManifest = Extender.loadYaml(root, appManifestFile, AppManifestConfiguration.class);

        assertTrue(appManifest != null);

        Map<String, Object> context = ExtenderUtil.getAppManifestContext(appManifest, "x86_64-osx", null);

        List<String> expectedItems = new ArrayList<>();
        expectedItems.add("-fno-exceptions"); // common
        expectedItems.add("-fno-rtti"); // x86-osx

        assertEquals( expectedItems, context.get("flags") );
    }

    @Test
    public void testMergedContexts() throws IOException, ExtenderException {

        File jobDir = new File("test-data/manifest_override");
        jobDir.mkdirs();
        File uploadDir = new File(jobDir, "upload");
        uploadDir.mkdirs();
        File buildDir = new File(jobDir, "build");
        buildDir.mkdirs();
        buildDir.deleteOnExit();
        File sdk = new File("test-data/sdk/a/defoldsdk");


        Map<String, String> env = new HashMap<>();

        // TODO: Read these from the Dockerfile itself
        env.put("PLATFORMSDK_DIR", "/opt/platformsdk");
        env.put("MANIFEST_MERGE_TOOL", "/opt/local/bin/manifestmergetool.jar");
        env.put("XCODE_14_VERSION", "14.2");
        env.put("IOS_16_VERSION", "16.2");
        env.put("LIB_TAPI_1_6_PATH", "/usr/local/tapi1.6/lib");
        env.put("MACOS_13_VERSION", "13.1");
        env.put("XCODE_14_CLANG_VERSION", "14.0.0");
        env.put("SWIFT_5_5_VERSION", "5.5");
        env.put("SYSROOT", "/opt/platformsdk/MacOSX13.1.sdk");
        env.put("LD_LIBRARY_PATH", "/usr/local/tapi1.6/lib");

        Extender extender = new Extender("x86_64-osx", sdk, jobDir, uploadDir, buildDir, env);
        Map<String, Object> mergedAppContext = extender.getMergedAppContext();
        System.out.printf("MAWE DEBUG UNITTEST\n");

        List<String> libsOriginal = Arrays.asList("engine_release", "engine_service_null", "profile_null", "remotery_null", "profilerext_null", "record_null");
        List<String> libsExpected = Arrays.asList("engine_release", "engine_service_null", "remotery_null", "record_null");

        assertEquals(libsExpected, mergedAppContext.getOrDefault("libs", new ArrayList<String>()));

        Map<String, Object> extensionContext = extender.getMergedExtensionContext("Extension1");

        assertEquals("EXTENSION1", extensionContext.getOrDefault("extension_name_upper", "null"));
        List<String> excluded = (List<String>)extensionContext.getOrDefault("excludeLibs", new ArrayList<String>());
        assertTrue(excluded.contains("profilerext_null"));
        assertTrue(excluded.contains("profile_null"));

        uploadDir.delete();
        assertTrue(true);
    }

    @Test
    public void testGetAppmanifest() throws IOException, ExtenderException {
        File root = new File("test-data");

        AppManifestConfiguration appManifest = Extender.loadYaml(root, new File("test-data/extendertest.platformnull.appmanifest"), AppManifestConfiguration.class);
        // previous issue was that it threw a null pointer exception
        ExtenderUtil.getAppManifestContext(appManifest, "x86_64-osx", null);
    }

    @Test
    public void testGetManifestContext() throws IOException, ExtenderException {
        File root = new File("test-data");
        ManifestConfiguration manifestConfig = Extender.loadYaml(root, new File("test-data/extendertest.emptycontext.manifest"), ManifestConfiguration.class);
        // previous issue was that it returned a null pointer
        Map<String, Object> manifestContext = Extender.getManifestContext("x86_64-osx", manifestConfig);
        assertNotEquals(null, manifestContext);
    }

    @Test
    public void testAppManifestContextWithVariant() throws IOException, ExtenderException {

        File root = new File("test-data");
        File appManifestFile = new File("test-data/extendertest.appmanifest");
        File baseManifestFile = new File("test-data/headless.appmanifest");

        AppManifestConfiguration appManifest = Extender.loadYaml(root, appManifestFile, AppManifestConfiguration.class);
        assertTrue(appManifest != null);

        AppManifestConfiguration baseManifest = Extender.loadYaml(root, baseManifestFile, AppManifestConfiguration.class);
        assertTrue(baseManifest != null);

        Map<String, Object> context = ExtenderUtil.getAppManifestContext(appManifest, "x86_64-osx", baseManifest);

        List<String> expectedItems = new ArrayList<>();
        expectedItems.add("DefaultSoundDevice"); // base x86-osx
        expectedItems.add("AudioDecoderWav"); // base x86-osx
        expectedItems.add("AudioDecoderStbVorbis"); // base x86-osx
        expectedItems.add("AudioDecoderTremolo"); // base x86-osx
        expectedItems.add("SymbolA"); // common
        expectedItems.add("SymbolB"); // x86_64-osx

        assertEquals( expectedItems, context.get("excludeSymbols") );
    }
}
