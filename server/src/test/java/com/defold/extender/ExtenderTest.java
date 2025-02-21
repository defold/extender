package com.defold.extender;

import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExtenderTest {

    static Map<String, String> envFileToMap(File inputFile) {
        Map<String, String> result = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            String line = reader.readLine();
            while (line != null) {
                String[] splitted = line.split("=");
                result.put(splitted[0], splitted[1]);
                line = reader.readLine();
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;
    }

    static Map<String, String> createEnv()
    {
        Map<String, String> env = new HashMap<>();
        env.putAll(envFileToMap(new File("envs/.env")));
        env.putAll(envFileToMap(new File("envs/macos.env")));

        env.put("PLATFORMSDK_DIR", "/opt/platformsdk");
        env.put("MANIFEST_MERGE_TOOL", "/opt/local/bin/manifestmergetool.jar");
        env.put("ZIG_PATH_0_11", "/opt/platformsdk/zig-0.11.0");

        return env;
    }

    static void checkArray(String[] expected, Object obj)
    {
        List<String> l = (List<String>)obj;
        if (l == null)
            l = new ArrayList<String>();
        assertEquals(Arrays.asList(expected), l);
    }

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

        Map<String, String> env = createEnv();

        Extender extender = new Extender.Builder()
                            .setPlatform("x86_64-osx")
                            .setSdk(sdk)
                            .setJobDirectory(jobDir)
                            .setUploadDirectory(uploadDir)
                            .setBuildDirectory(buildDir)
                            .setEnv(env)
                            .build();

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

    @Test
    public void testReceiveFiles(@TempDir File uploadDirectory) throws IOException, FileUploadException, ExtenderException {
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
        MultipartHttpServletRequest badRequest = createMultipartHttpRequest(files);
        {
            assertThrows(ExtenderException.class, () -> {
                ExtenderController.receiveUpload(badRequest, uploadDirectory);
            });
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

    @Test
    public void testValidateFilenames() {
        // Should be fine
        assertDoesNotThrow(() -> { ExtenderController.validateFilename("include/test.h"); });
        assertDoesNotThrow(() -> { ExtenderController.validateFilename("include/test+framework.h"); });
        assertDoesNotThrow(() -> { ExtenderController.validateFilename("src/test.c++"); });
        assertDoesNotThrow(() -> { ExtenderController.validateFilename("src/icon@2x.png"); });

        // Should throw error
        assertThrows(ExtenderException.class, () -> { ExtenderController.validateFilename("+foobar.h"); });
        assertThrows(ExtenderException.class, () -> { ExtenderController.validateFilename("include/foo;echo foo;.h"); });
        assertThrows(ExtenderException.class, () -> { ExtenderController.validateFilename("../../etc/passwd"); });
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
            List<String> result = ExtenderUtil.collectFilesByName(new File("test-data/ext/lib/x86_64-osx"), "lib(.+)\\.a");
            String[] expected = {"alib"};
            checkArray(expected, result);
        }
        {
            List<String> result = ExtenderUtil.collectDirsByName(new File("test-data/ext/lib/x86_64-osx"), Extender.FRAMEWORK_RE);
            String[] expected = {"blib"};
            checkArray(expected, result);
        }
        {
            List<String> result = ExtenderUtil.collectFilesByName(new File("test-data/ext/lib/x86_64-win32"), "(.+)\\.lib");
            String[] expected = {"alib"};
            checkArray(expected, result);
        }
    }

    @Test
    public void testCollectJars() {
        String[] endings = {"test-data/ext/lib/android/Dummy.jar", "test-data/ext/lib/android/JarDep.jar",
                            "test-data/ext/lib/android/VeryLarge1.jar", "test-data/ext/lib/android/VeryLarge2.jar",
                            "test-data/ext/lib/android/meta-inf.jar"};
        List<String> paths = ExtenderUtil.collectFilesByPath(new File("test-data/ext/lib/android"), Extender.JAR_RE);
        assertEquals(endings.length, paths.size());


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

        Map<String, String> env = createEnv();

        Extender extender = new Extender.Builder()
                            .setPlatform("x86_64-osx")
                            .setSdk(sdk)
                            .setJobDirectory(jobDir)
                            .setUploadDirectory(uploadDir)
                            .setBuildDirectory(buildDir)
                            .setEnv(env)
                            .build();
        Map<String, Object> mergedAppContext = extender.getMergedAppContext();

        List<String> libsOriginal = Arrays.asList("engine_release", "engine_service_null", "profile_null", "remotery_null", "profilerext_null", "record_null");
        List<String> libsExpected = Arrays.asList("clang_rt.osx", "engine_release", "engine_service_null", "remotery_null", "record_null");
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

    static void writeYaml(File file, Map<String, Object> map) throws IOException
    {
        Yaml yaml = new Yaml();
        String text = yaml.dump(map);
        FileUtils.writeStringToFile(file, text, Charset.defaultCharset(), false);
    }

    static File setupJobFolder(String variant) throws IOException
    {
        File jobDir = Files.createTempDirectory(variant).toFile();
        jobDir.deleteOnExit();
        File uploadDir = new File(jobDir, "upload");
        uploadDir.mkdirs();
        File appDir = new File(uploadDir, "_app");
        appDir.mkdirs();

        File buildDir = new File(jobDir, "build");
        buildDir.mkdirs();

        Map<String, Object> map = new HashMap<>();
        Map<String, Object> context = new HashMap<>();
        Map<String, Object> platforms = new HashMap<>();
        map.put("context", context);
        map.put("platforms", platforms);
        context.put(Extender.APPMANIFEST_BASE_VARIANT_KEYWORD, variant);

        writeYaml(new File(appDir, Extender.APPMANIFEST_FILENAME), map);
        return jobDir;
    }

    @Test
    public void testVariantHeadless() throws IOException, InterruptedException, ExtenderException {
        File jobDir = setupJobFolder("headless");
        File uploadDir = new File(jobDir, "upload");
        File buildDir = new File(jobDir, "build");

        File sdk = new File("test-data/sdk/a/defoldsdk");

        Map<String, String> env = createEnv();

        Extender extender = new Extender.Builder()
                            .setPlatform("x86_64-linux")
                            .setSdk(sdk)
                            .setJobDirectory(jobDir)
                            .setUploadDirectory(uploadDir)
                            .setBuildDirectory(buildDir)
                            .setEnv(env)
                            .build();

        Map<String, Object> map = extender.getMergedAppContext();

        // And, verify the variant changes libraries
        String[] excludeLibs = {"record", "vpx", "sound", "tremolo", "graphics", "hid"};
        checkArray(excludeLibs, map.get("excludeLibs"));

        String[] excludeDynamicLibs = {"openal", "Xext", "X11", "Xi", "GL", "GLU"};
        checkArray(excludeDynamicLibs, map.get("excludeDynamicLibs"));

        String[] excludeSymbols = {"DefaultSoundDevice", "AudioDecoderWav", "AudioDecoderStbVorbis", "AudioDecoderTremolo", "GraphicsAdapterOpenGL", "GraphicsAdapterVulkan"};
        checkArray(excludeSymbols, map.get("excludeSymbols"));

        // And, verify the resulting libraries

        String[] libs = {"record_null", "sound_null", "graphics_null", "hid_null"};
        checkArray(libs, map.get("libs"));

        String[] dynamicLibs = {"pthread", "m", "dl"};
        checkArray(dynamicLibs, map.get("dynamicLibs"));

        FileUtils.deleteQuietly(jobDir);
        assertTrue(true);
    }

    @Test
    public void testVariantRelease() throws IOException, InterruptedException, ExtenderException {
        File jobDir = setupJobFolder("release");
        File uploadDir = new File(jobDir, "upload");
        File buildDir = new File(jobDir, "build");

        File sdk = new File("test-data/sdk/a/defoldsdk");

        Map<String, String> env = createEnv();

        Extender extender = new Extender.Builder()
                            .setPlatform("x86_64-linux")
                            .setSdk(sdk)
                            .setJobDirectory(jobDir)
                            .setUploadDirectory(uploadDir)
                            .setBuildDirectory(buildDir)
                            .setEnv(env)
                            .build();

        Map<String, Object> map = extender.getMergedAppContext();

        // And, verify the variant changes libraries
        String[] excludeLibs = {"engine", "engine_service", "profile", "remotery", "profilerext", "record", "vpx"};
        checkArray(excludeLibs, map.get("excludeLibs"));

        String[] excludeDynamicLibs = {};
        checkArray(excludeDynamicLibs, map.get("excludeDynamicLibs"));

        String[] excludeSymbols = {};
        checkArray(excludeSymbols, map.get("excludeSymbols"));

        // And, verify the resulting libraries

        String[] libs = {"engine_release", "engine_service_null", "profile_null", "remotery_null", "profilerext_null", "record_null"};
        checkArray(libs, map.get("libs"));

        String[] dynamicLibs = {"openal", "Xext", "X11", "Xi", "GL", "GLU", "pthread", "m", "dl"};
        checkArray(dynamicLibs, map.get("dynamicLibs"));

        FileUtils.deleteQuietly(jobDir);
        assertTrue(true);
    }
}
