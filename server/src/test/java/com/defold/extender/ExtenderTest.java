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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
import java.util.zip.ZipEntry;

public class ExtenderTest {
    // Verifies that compiled Android resource directories include their input index so equal AAR names cannot collide.
    @Test
    public void testCompiledResourceDirectoryNamesAreCollisionSafe(@TempDir File temporaryDirectory) {
        File first = new File(temporaryDirectory, "first/common-1.0/res");
        File second = new File(temporaryDirectory, "second/common-1.0/res");

        assertEquals("0000-common-1.0", Extender.getCompiledResourceDirectoryName(0, first));
        assertEquals("0001-common-1.0", Extender.getCompiledResourceDirectoryName(1, second));
        assertNotEquals(
                Extender.getCompiledResourceDirectoryName(0, first),
                Extender.getCompiledResourceDirectoryName(1, second));
    }

    // Verifies that returned resource packages keep legacy names when unique and add deterministic suffixes on collisions.
    @Test
    public void testReturnedResourcePackageNamesPreserveLegacyNamesAndResolveCollisions(
            @TempDir File temporaryDirectory) throws IOException {
        List<String> resourceDirectories = List.of(
                new File(temporaryDirectory, "first/common-1.0/res").getAbsolutePath(),
                new File(temporaryDirectory, "second/common-1.0/res").getAbsolutePath(),
                new File(temporaryDirectory, "third/common-1.0-0001/res").getAbsolutePath(),
                new File(temporaryDirectory, "fourth/unique-1.0/res").getAbsolutePath());

        assertEquals(
                List.of(
                        "common-1.0",
                        "common-1.0-0001-1",
                        "common-1.0-0001",
                        "unique-1.0"),
                Extender.getReturnedResourcePackageNames(resourceDirectories, Map.of()));
    }

    // Verifies that Gradle artifact identities, rather than transient exploded-directory names, determine returned package names.
    @Test
    public void testReturnedResourcePackageNamesUseGradleArtifactIdentity(
            @TempDir File temporaryDirectory) throws IOException {
        File firstPackage = new File(temporaryDirectory, "transforms/first/jetified-library");
        File secondPackage = new File(temporaryDirectory, "transforms/second/jetified-library");
        List<String> resourceDirectories = List.of(
                new File(firstPackage, "res").getAbsolutePath(),
                new File(secondPackage, "res").getAbsolutePath());
        String legacyName = "com.example-library-1.0.aar";

        assertEquals(
                List.of(legacyName, legacyName + "-0001"),
                Extender.getReturnedResourcePackageNames(
                        resourceDirectories,
                        Map.of(
                                firstPackage.getCanonicalFile(), legacyName,
                                secondPackage.getCanonicalFile(), legacyName)));
    }

    static Map<String, String> createEnv()
    {
        Map<String, String> env = new HashMap<>();
        env.putAll(TestUtils.envFileToMap(new File("envs/.env")));
        env.putAll(TestUtils.envFileToMap(new File("envs/macos.env")));

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

    static PlatformConfig mergePlatformConfig(Configuration config, String platform) throws ExtenderException
    {
        PlatformConfig platformConfig = new PlatformConfig();
        platformConfig.context = new HashMap<>(config.context);
        for (String platformAlt : ExtenderUtil.getPlatformAlternatives(platform)) {
            PlatformConfig platformConfigAlt = config.platforms.get(platformAlt);
            if (platformConfigAlt != null) {
                ExtenderUtil.mergeObjects(platformConfig, platformConfigAlt);
            }
        }
        return platformConfig;
    }

    @Test
    public void testExtender(@TempDir File jobDir) throws IOException, InterruptedException, ExtenderException {
        File uploadDir = new File(jobDir, "upload");
        uploadDir.mkdirs();
        File buildDir = new File(jobDir, "build");
        buildDir.mkdirs();
        File sdk = new File("test-data/sdk/a/defoldsdk");

        Map<String, String> env = createEnv();

        assertDoesNotThrow(() -> new Extender.Builder()
                            .setPlatform("x86_64-osx")
                            .setSdk(sdk)
                            .setJobDirectory(jobDir)
                            .setUploadDirectory(uploadDir)
                            .setBuildDirectory(buildDir)
                            .setEnv(env)
                            .build());

        assertTrue(uploadDir.delete());
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
            List<String> result = ExtenderUtil.collectDirsByName(new File("test-data/ext/lib/x86_64-osx"), ExtenderConst.FRAMEWORK_RE);
            String[] expected = {"blib"};
            checkArray(expected, result);
        }
        {
            List<String> result = ExtenderUtil.collectFilesByName(new File("test-data/ext/lib/x86_64-win32"), "(.+)\\.lib");
            String[] expected = {"alib"};
            checkArray(expected, result);
        }
    }

    // The Android platform config in build.yml templates its env from these, so they have to be
    // present when creating an Extender for an Android platform. The values are never executed here.
    static Map<String, String> createAndroidEnv()
    {
        Map<String, String> env = createEnv();
        env.put("ANDROID_R8", "/opt/android/r8.jar");
        env.put("ANDROID_R8_VERSION", "8.13.19");
        env.put("ANDROID_LIBRARYJAR", "/opt/android/android.jar");
        env.put("ANDROID_NDK_PATH", "/opt/android/ndk");
        env.put("ANDROID_NDK_SYSROOT", "/opt/android/ndk/sysroot");
        env.put("ANDROID_NDK_BIN_PATH", "/opt/android/ndk/bin");
        env.put("ANDROID_NDK_API_VERSION", "19");
        env.put("ANDROID_64_NDK_API_VERSION", "21");
        env.put("ANDROID_SDK_VERSION", "36");
        env.put("ANDROID_SDK_BUILD_TOOLS_PATH", "/opt/android/build-tools");
        return env;
    }

    // Verifies that the Android SDK fixture uses only R8 configuration and discovers only the new .keep rule format.
    @Test
    @SuppressWarnings("deprecation")
    public void testAndroidSdkUsesOnlyR8Configuration() throws Exception {
        File root = new File("test-data");
        File sdk = new File(root, "sdk/a/defoldsdk");
        Configuration config = Extender.loadYaml(root, new File(sdk, "extender/build.yml"), Configuration.class);
        PlatformConfig android = mergePlatformConfig(config, "armv7-android");

        assertTrue(android.r8Cmd.contains("com.android.tools.r8.R8"));
        assertFalse(android.r8Cmd.contains("--main-dex-rules"));
        assertTrue(android.r8Cmd.contains("--pg-conf \"{{{.}}}\""));
        assertTrue(android.r8Cmd.contains("{{#jars}}\"{{{.}}}\""));
        assertTrue(android.dxCmd.contains("--min-api {{minAndroidSdkVersion}}"));
        assertEquals("{{env.R8_VERSION}}", android.r8Version);
        assertEquals("(?i).+(\\.keep)$", android.r8RuleSourceRe);
        assertTrue(android.aapt2linkCmd.contains("{{#useR8}}--proguard \"{{{aaptKeepRules}}}\""));
        assertFalse(android.aapt2linkCmd.contains("--proguard-main-dex"));
        assertNull(android.proGuardCmd);
        assertNull(android.proGuardSourceRe);

        Collection<File> candidates = List.of(
                new File("manifests/android/extension.keep"),
                new File("manifests/android/extension.pro"));
        List<File> rules = ExtenderUtil.filterFiles(candidates, android.r8RuleSourceRe);
        assertEquals(List.of(new File("manifests/android/extension.keep")), rules);
    }

    // Verifies that legacy ProGuard-era SDK YAML still loads while app.pro remains ignored and does not request R8.
    @Test
    @SuppressWarnings("deprecation")
    public void testLegacyAndroidSdkProguardConfigurationIsAcceptedAndIgnored(@TempDir File tempDir) throws Exception {
        String buildYaml = Files.readString(
                new File("test-data/sdk/a/defoldsdk/extender/build.yml").toPath());
        buildYaml = buildYaml
                .replace("        R8:                       \"{{env.ANDROID_R8}}\"\n", "")
                .replace("        R8_VERSION:               \"{{env.ANDROID_R8_VERSION}}\"\n", "")
                .replace(
                        "        LIBRARYJAR:               \"{{env.ANDROID_LIBRARYJAR}}\"\n",
                        "        PROGUARD:                 \"{{env.ANDROID_PROGUARD}}\"\n"
                                + "        LIBRARYJAR:               \"{{env.ANDROID_LIBRARYJAR}}\"\n")
                .replace(
                        "    r8Cmd: 'java -cp \"{{{env.R8}}}\" com.android.tools.r8.R8 --release --min-api {{minAndroidSdkVersion}} --lib \"{{{env.LIBRARYJAR}}}\" --pg-map-output \"{{{mapping}}}\" --output \"{{{classes_dex_dir}}}\" {{#rules}}--pg-conf \"{{{.}}}\" {{/rules}} {{#jars}}\"{{{.}}}\" {{/jars}}'\n"
                                + "    r8Version: '{{env.R8_VERSION}}'\n"
                                + "    r8RuleSourceRe: '(?i).+(\\.keep)$'\n",
                        "    proGuardCmd: 'legacy-proguard-command'\n"
                                + "    proGuardSourceRe: '(?i).+(\\.pro)$'\n")
                .replace(
                        " {{#useR8}}--proguard \"{{{aaptKeepRules}}}\" {{/useR8}}",
                        " ");

        File sdk = new File(tempDir, "legacy-sdk");
        File sdkExtenderDir = new File(sdk, "extender");
        assertTrue(sdkExtenderDir.mkdirs());
        File buildFile = new File(sdkExtenderDir, "build.yml");
        Files.writeString(buildFile.toPath(), buildYaml);

        Configuration config = Extender.loadYaml(tempDir, buildFile, Configuration.class);
        PlatformConfig android = mergePlatformConfig(config, "armv7-android");
        assertEquals("legacy-proguard-command", android.proGuardCmd);
        assertEquals("(?i).+(\\.pro)$", android.proGuardSourceRe);
        assertEquals("{{env.ANDROID_PROGUARD}}", android.env.get("PROGUARD"));
        assertNull(android.r8Cmd);
        assertFalse(android.aapt2linkCmd.contains("useR8"));
        assertFalse(android.aapt2linkCmd.contains("aaptKeepRules"));
        assertFalse(android.aapt2linkCmd.contains("--proguard"));

        File uploadWithoutProguard = new File(tempDir, "upload-without-proguard");
        File buildWithoutProguard = new File(tempDir, "build-without-proguard");
        assertTrue(uploadWithoutProguard.mkdirs());
        assertTrue(buildWithoutProguard.mkdirs());
        assertFalse(R8Builder.isRequested(uploadWithoutProguard));

        assertDoesNotThrow(() -> new Extender.Builder()
                .setPlatform("armv7-android")
                .setSdk(sdk)
                .setJobDirectory(tempDir)
                .setUploadDirectory(uploadWithoutProguard)
                .setBuildDirectory(buildWithoutProguard)
                .setEnv(createAndroidEnv())
                .build());

        File uploadWithProguard = new File(tempDir, "upload-with-proguard");
        File appDir = new File(uploadWithProguard, "_app");
        File buildWithProguard = new File(tempDir, "build-with-proguard");
        assertTrue(appDir.mkdirs());
        assertTrue(buildWithProguard.mkdirs());
        Files.writeString(new File(appDir, "app.pro").toPath(), "-keep class Legacy");
        assertFalse(R8Builder.isRequested(uploadWithProguard));

        assertDoesNotThrow(() -> new Extender.Builder()
                .setPlatform("armv7-android")
                .setSdk(sdk)
                .setJobDirectory(tempDir)
                .setUploadDirectory(uploadWithProguard)
                .setBuildDirectory(buildWithProguard)
                .setEnv(createAndroidEnv())
                .build());
    }

    // Verifies that consumer rules and legacy .pro metadata are excluded from runtime META-INF copying.
    @Test
    public void testConsumerRulesAreNotCopiedAsRuntimeMetaInfResources() {
        assertFalse(ExtenderUtil.isMetaInfEntryValuable(new ZipEntry("META-INF/proguard/rules.pro")));
        assertFalse(ExtenderUtil.isMetaInfEntryValuable(new ZipEntry("META-INF/proguard/rules.keep")));
        assertFalse(ExtenderUtil.isMetaInfEntryValuable(new ZipEntry("META-INF/com.android.tools/r8/rules.keep")));
        assertFalse(ExtenderUtil.isMetaInfEntryValuable(new ZipEntry(
                "META-INF/com.android.tools/r8-from-0.0.0-arbitrary/rules.pro")));
        assertTrue(ExtenderUtil.isMetaInfEntryValuable(new ZipEntry(
                "META-INF/com.android.tools/r8foo/not-a-rule.txt")));
        assertTrue(ExtenderUtil.isMetaInfEntryValuable(new ZipEntry(
                "META-INF/com.android.tools/lint/model.xml")));
        assertTrue(ExtenderUtil.isMetaInfEntryValuable(new ZipEntry("META-INF/services/com.example.Service")));
        assertFalse(ExtenderUtil.isMetaInfEntryValuable(new ZipEntry("META-INF/example/info.pro")));
    }

    // An .aar in an extension is unpacked into the same exploded layout as a Maven resolved .aar:
    // a directory named "*.aar" holding classes.jar, libs/, res/, assets/ and the AndroidManifest.
    @Test
    public void testResolveLocalAars() throws IOException, ExtenderException {
        File jobDir = Files.createTempDirectory("localaar").toFile();
        jobDir.deleteOnExit();
        File uploadDir = new File(jobDir, "upload");
        File buildDir = new File(jobDir, "build");
        buildDir.mkdirs();

        // an extension shipping a local .aar next to its jars
        File extDir = new File(uploadDir, "myext");
        FileUtils.copyFile(new File("test-data/ext/ext.manifest"), new File(extDir, "ext.manifest"));
        FileUtils.copyFile(new File("test-data/ext/lib/android/LocalAar.aar"), new File(extDir, "lib/android/LocalAar.aar"));

        Extender extender = new Extender.Builder()
                            .setPlatform("armv7-android")
                            .setSdk(new File("test-data/sdk/a/defoldsdk"))
                            .setJobDirectory(jobDir)
                            .setUploadDirectory(uploadDir)
                            .setBuildDirectory(buildDir)
                            .setEnv(createAndroidEnv())
                            .build();

        extender.resolveLocalAars();

        File unpacked = new File(buildDir, "local_aars/myext-LocalAar.aar");
        assertTrue(unpacked.isDirectory());
        assertTrue(new File(unpacked, "classes.jar").exists());
        assertTrue(new File(unpacked, "libs/InnerJar.jar").exists());
        assertTrue(new File(unpacked, "res/values/strings.xml").exists());
        assertTrue(new File(unpacked, "assets/local_aar.txt").exists());
        assertTrue(new File(unpacked, "AndroidManifest.xml").exists());

        List<String> extensionOwnedJars = extender.getExtensionLocalAarJars(extDir);
        assertEquals(
                List.of(
                        new File(unpacked, "classes.jar").getAbsolutePath(),
                        new File(unpacked, "libs/InnerJar.jar").getAbsolutePath()),
                extensionOwnedJars);
        R8Builder.ExtensionContext r8Context = R8Builder.createExtensionContext(
                extDir,
                extensionOwnedJars,
                "(?i).+(\\.keep)$");
        assertEquals(extensionOwnedJars, r8Context.protectedJars);

        FileUtils.deleteQuietly(jobDir);
    }

    @Test
    public void testCollectAars() {
        List<String> paths = ExtenderUtil.collectFilesByPath(new File("test-data/ext/lib/android"), ExtenderConst.AAR_RE);
        assertEquals(1, paths.size());
        assertTrue(paths.get(0).endsWith("test-data/ext/lib/android/LocalAar.aar"));
    }

    @Test
    public void testCollectJars() {
        String[] endings = {"test-data/ext/lib/android/Dummy.jar", "test-data/ext/lib/android/JarDep.jar",
                            "test-data/ext/lib/android/VeryLarge1.jar", "test-data/ext/lib/android/VeryLarge2.jar",
                            "test-data/ext/lib/android/meta-inf.jar"};
        List<String> paths = ExtenderUtil.collectFilesByPath(new File("test-data/ext/lib/android"), ExtenderConst.JAR_RE);
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
        List<String> result = ExtenderUtil.collectFilesByPath(new File("test-data/ext/lib/js-web"), ExtenderConst.JS_RE);
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
    public void testOsxCompileCommandCarriesSdkFlags() throws IOException, ExtenderException {
        File root = new File("test-data");
        File sdk = new File(root, "sdk/a/defoldsdk");
        Configuration config = Extender.loadYaml(root, new File(sdk, "extender/build.yml"), Configuration.class);

        PlatformConfig platformConfig = mergePlatformConfig(config, "arm64-osx");

        assertTrue(platformConfig.compileCmd.contains("{{#clangArch}}-arch {{{clangArch}}} {{/clangArch}}"));
        assertTrue(platformConfig.compileCmd.contains("{{#clangTarget}}-target {{{clangTarget}}} {{/clangTarget}}"));
        assertTrue(platformConfig.compileCmd.contains("{{#clangArch}}-m64 {{/clangArch}}"));
        assertTrue(platformConfig.compileCmd.contains("-isysroot {{env.SYSROOT}}"));
        assertEquals("arm64", platformConfig.context.get("clangArch"));
        assertEquals("arm64-apple-darwin19", platformConfig.context.get("clangTarget"));
        List<String> swiftFlags = (List<String>)platformConfig.context.get("swiftFlags");
        assertTrue(swiftFlags.contains("arm64-apple-macosx{{osMinVersion}}"));

        List<String> flags = (List<String>)platformConfig.context.get("flags");
        assertFalse(flags.contains("-arch"));
        assertFalse(flags.contains("-target"));
        assertFalse(flags.contains("-isysroot"));
        assertFalse(flags.contains("{{env.SYSROOT}}"));
        List<String> libPaths = (List<String>)platformConfig.context.get("libPaths");
        assertTrue(libPaths.contains("{{env.PLATFORMSDK_DIR}}/XcodeDefault{{env.XCODE_VERSION}}.xctoolchain/usr/lib/swift/macosx"));

        Map<String, String> env = new HashMap<>();
        env.put("SYSROOT", "/opt/platformsdk/MacOSX.sdk");
        env.put("MACOS_VERSION_MIN", "10.15");

        Map<String, Object> renderContext = new HashMap<>(platformConfig.context);
        renderContext.put("env", env);
        renderContext.put("ext", new HashMap<String, Object>());
        renderContext.put("extension_name_upper", "TEST");
        renderContext.put("src", "source.mm");
        renderContext.put("tgt", "source.o");
        String renderedCompileCmd = new TemplateExecutor().execute(platformConfig.compileCmd, renderContext);
        assertTrue(renderedCompileCmd.contains("-arch arm64"));
        assertTrue(renderedCompileCmd.contains("-target arm64-apple-darwin19"));
        assertTrue(renderedCompileCmd.contains("-m64"));
        assertTrue(renderedCompileCmd.contains("-isysroot /opt/platformsdk/MacOSX.sdk"));

        PlatformConfig legacyPlatformConfig = mergePlatformConfig(config, "x86-osx");
        Map<String, Object> legacyRenderContext = new HashMap<>(legacyPlatformConfig.context);
        legacyRenderContext.put("env", env);
        legacyRenderContext.put("ext", new HashMap<String, Object>());
        legacyRenderContext.put("extension_name_upper", "TEST");
        legacyRenderContext.put("src", "source.mm");
        legacyRenderContext.put("tgt", "source.o");
        String legacyRenderedCompileCmd = new TemplateExecutor().execute(legacyPlatformConfig.compileCmd, legacyRenderContext);
        assertFalse(legacyRenderedCompileCmd.contains("-arch "));
        assertFalse(legacyRenderedCompileCmd.contains("-target "));
        assertFalse(legacyRenderedCompileCmd.contains("-m64 "));
        assertTrue(legacyRenderedCompileCmd.contains("-isysroot /opt/platformsdk/MacOSX.sdk"));

        PlatformConfig x86_64PlatformConfig = mergePlatformConfig(config, "x86_64-osx");
        List<String> x86_64SwiftFlags = (List<String>)x86_64PlatformConfig.context.get("swiftFlags");
        assertTrue(x86_64SwiftFlags.contains("x86_64-apple-macosx{{osMinVersion}}"));
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
        context.put(ExtenderBuildState.APPMANIFEST_BASE_VARIANT_KEYWORD, variant);

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
