package com.defold.extender;

import com.defold.extender.client.*;
import com.defold.extender.process.ProcessExecutor;
import com.google.common.collect.Lists;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Tag("integration")
public class IntegrationTest {
    private static final int EXTENDER_PORT = 9000;

    static {
        LoggingSystem.get(ClassLoader.getSystemClassLoader()).setLogLevel(Logger.ROOT_LOGGER_NAME, LogLevel.INFO);
    }

    private static class Version
    {
        public int major;
        public int middle;
        public int minor;

        public Version(int major, int middle, int minor)
        {
            this.major = major;
            this.middle = middle;
            this.minor = minor;
        }

        @Override
        public String toString() {
            return String.format("%d.%d.%03d", major, middle, minor);
        }

        boolean isVersion(int major, int middle, int minor) {
            return this.major == major && this.middle == middle && this.minor == minor;
        }

        boolean isLessThan(int major, int middle, int minor) {
            return this.major < major || this.middle < middle || this.minor < minor;
        }

        boolean isGreaterThan(int major, int middle, int minor) {
            return this.major > major || this.middle > middle || this.minor > minor;
        }
    }

    private static class DefoldVersion
    {
        public String sha1;
        public Version version;
        public String[] platforms;

        public DefoldVersion(String sha1, Version version, String[] platforms)
        {
            this.sha1 = sha1;
            this.version = version;
            this.platforms = platforms;
        }
    }

    private static class TestConfiguration {
        public DefoldVersion version;
        public String platform = "";

        public TestConfiguration(DefoldVersion version, String platform) {
            this.version = version;
            this.platform = platform;
        }

        @Override
        public String toString() {
            return String.format("%s sha1(%s) %s", version.version.toString(), version.sha1, platform);
        }
    }

    public static List<TestConfiguration> data() {

        boolean ciBuild = System.getenv("GITHUB_WORKSPACE") != null;

        ArrayList<TestConfiguration> data = new ArrayList<>();

        DefoldVersion[] versions = {
                // "a" is a made up sdk where we can more easily test build.yml fixes
                // new DefoldVersion("a", new Version(0, 0, 0), new String[] {"armv7-android", "x86_64-win32"} ),

                // // 2025-04-14 https://github.com/defold/defold/releases/tag/1.10.0
                new DefoldVersion("591eb496d52f4140bc2c7de547131f1b9408b9b4", new Version(1, 10, 0), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),

                // // 2024-05-14 https://github.com/defold/defold/releases/tag/1.10.1
                new DefoldVersion("d8e6e73a8efac6b9a72783027867e547b6a363e4", new Version(1, 10, 1), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // 2024-06-11 https://github.com/defold/defold/releases/tag/1.10.2
                new DefoldVersion("7a0e23b3fcab4c5db82f2b32f5d8ac5df9467c9d", new Version(1, 10, 2), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // 2025-07-07 https://github.com/defold/defold/releases/tag/1.10.3
                new DefoldVersion("1c76521bb8b08c63ef619aa8a5ab563dddf7b3cf", new Version(1, 10, 3), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // 2025-08-04 https://github.com/defold/defold/releases/tag/1.10.4
                new DefoldVersion("1aafd0a262ff40214ed7f51302d92fa587c607ef", new Version(1, 10, 4), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // Use test-data/createdebugsdk.sh to package your preferred platform sdk and it ends up in the sdk/debugsdk folder
                // Then you can write your tests without waiting for the next release
                //new DefoldVersion("debugsdk", new Version(1, 2, 104), new String[] {"js-web"}),
        };

        DefoldVersion[] ciVersions = {
            versions[0],
            versions[versions.length-1],
        };

        if (ciBuild) {
            versions = ciVersions;
        }

        for( int i = 0; i < versions.length; ++i )
        {
            for (String platform : versions[i].platforms) {
                data.add(new TestConfiguration(versions[i], platform));
            }
        }

        return data;
    }

    public IntegrationTest() { }

    @BeforeAll
    public static void beforeClass() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.putEnv("COMPOSE_PROFILE", "test");
        processExecutor.putEnv("APPLICATION", "extender-test");
        processExecutor.putEnv("PORT", String.valueOf(EXTENDER_PORT));
        processExecutor.execute("scripts/start-test-server.sh");
        System.out.println(processExecutor.getOutput());

        long startTime = System.currentTimeMillis();

        // Wait for server to start in container.
        File cacheDir = Files.createTempDirectory("health_check").toFile();
        cacheDir.deleteOnExit();
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);

        int count = 100;
        for (int i  = 0; i < count; i++) {

            try {
                if (extenderClient.health()) {
                    System.out.println(String.format("Server started after %f seconds!", (System.currentTimeMillis() - startTime) / 1000.f));
                    break;
                }
            } catch (IOException e) {
                if (i == count-1) {
                    e.printStackTrace();
                }
            }
            System.out.println("Waiting for server to start...");
            Thread.sleep(2000);
        }

    }

    @AfterAll
    public static void afterClass() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.putEnv("APPLICATION", "extender-test");
        processExecutor.execute("scripts/stop-test-server.sh");
        System.out.println(processExecutor.getOutput());
    }

    private String[] getEngineNames(String platform) {
        if (platform.endsWith("android")) {
            return new String[]{"libdmengine.so"};
        }
        else if (platform.equals("js-web")) {
            return new String[]{"dmengine.js"};
        }
        else if (platform.equals("wasm-web")) {
            return new String[]{"dmengine.js", "dmengine.wasm"};
        }
        else if (platform.equals("wasm_pthread-web")) {
            return new String[]{"dmengine.js", "dmengine_pthread.wasm"};
        }
        else if (platform.endsWith("win32")) {
            return new String[]{"dmengine.exe"};
        }
        return new String[]{"dmengine"};
    }

    private String getLibName(String platform, String lib) {
        if (platform.endsWith("win32")) {
            return String.format("%s.lib", lib);
        }
        return String.format("lib%s.a", lib);
    }

        private String getDynamicLibName(String platform, String lib) {
        if (platform.endsWith("win32")) {
            return String.format("%s.dll", lib);
        } else if (ExtenderUtil.isAppleTarget(platform)) {
            return String.format("%s.dylib", lib);
        }
        return String.format("lib%s.so", lib);
    }

    private File doBuild(List<ExtenderResource> sourceFiles, TestConfiguration configuration) throws IOException, ExtenderClientException {
        File cacheDir = Files.createTempDirectory(String.format("%s-%s", configuration.platform, configuration.version.toString())).toFile();
        cacheDir.deleteOnExit();
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        String platform = configuration.platform;
        String sdkVersion = configuration.version.sha1;

        try {
            extenderClient.build(
                    platform,
                    sdkVersion,
                    sourceFiles,
                    destination,
                    log
            );
        } catch (ExtenderClientException e) {
            System.out.println("ERROR LOG:");
            System.out.println(new String(Files.readAllBytes(log.toPath())));
            throw e;
        }

        assertTrue(destination.length() > 0, "Resulting engine should be of a size greater than zero.");
        assertEquals(0, log.length(), "Log should be of size zero if successful.");

        ExtenderClientCache cache = new ExtenderClientCache(cacheDir);
        assertTrue(cache.getCachedBuildFile(configuration.platform).exists());

        try (ZipFile zipFile = new ZipFile(destination)) {
            String[] expectedEngineNames = getEngineNames(configuration.platform);
            for (String engineName : expectedEngineNames) {
                assertNotEquals(null, zipFile.getEntry( engineName ) );
            }

            if (configuration.platform.endsWith("android")) {
                // Add this when we've made sure that all android builds create a classes.dex
                assertNotEquals(null, zipFile.getEntry("classes.dex"));
            }
        }

        return destination;
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngine(TestConfiguration configuration) throws IOException, ExtenderClientException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext2/ext.manifest"),
                new FileExtenderResource("test-data/ext2/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
                new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "blib")))
        );

        doBuild(sourceFiles, configuration);
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildExtensionStdLib(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(!configuration.version.version.isVersion(0, 0, 0), "Only use with real sdk's");
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext_std/ext.manifest"),
                new FileExtenderResource("test-data/ext_std/include/std.h"),
                new FileExtenderResource("test-data/ext_std/src/test_ext.cpp"),
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource(String.format("test-data/ext_std/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "std")))
        );
        doBuild(sourceFiles, configuration);
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineWithBaseExtension(TestConfiguration configuration) throws IOException, ExtenderClientException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/include/ext.h"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
                new FileExtenderResource("test-data/ext_use_base_extension/ext.manifest"),
                new FileExtenderResource("test-data/ext_use_base_extension/src/test_ext.cpp"),
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml")
        );

        doBuild(sourceFiles, configuration);
    }

    private boolean checkClassesDexClasses(File buildZip, List<String> classes) throws IOException {
        Set<String> dexClasses = new HashSet<>();

        try (ZipFile zipFile = new ZipFile(buildZip)) {
            final Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!name.endsWith(".dex"))
                    continue;

                InputStream in = zipFile.getInputStream(entry);
                Path tmpClassesDexPath = Files.createTempFile("classes", "dex");
                Files.copy(in, tmpClassesDexPath, StandardCopyOption.REPLACE_EXISTING);

                // Verify that classes.dex contains our Dummy class
                DexFile dexFile = DexFileFactory.loadDexFile(tmpClassesDexPath.toFile().getAbsolutePath(), Opcodes.forApi(19));
                for (ClassDef classDef: dexFile.getClasses()) {
                    dexClasses.add(classDef.getType());
                }
            }
        }

        for (String cls : classes) {
            if (!dexClasses.contains(cls)) {
                System.err.println(String.format("Missing class %s", cls));
                return false;
            }
        }
        return true;
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidCheckClassesDex(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android"), "This test is only run for Android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/Dummy.jar"));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/dummy/Dummy;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidCheckClassesMultiDex(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android"), "This test is only run for Android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/Dummy.jar"),
                new FileExtenderResource("test-data/ext/lib/android/VeryLarge1.jar"),
                new FileExtenderResource("test-data/ext/lib/android/VeryLarge2.jar"));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/dummy/Dummy;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidCheckCompiledJava(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android"), "This test is only run for Android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/src/Test.java"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/Dummy.jar"));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/dummy/Dummy;", "Lcom/defold/Test;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    /*
     * Test if a Java source can import classes specified in a supplied Jar file.
     */
    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidJavaJarDependency(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android"), "This test is only run for Android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/src/TestJar.java"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/JarDep.jar"));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/JarDep;", "Lcom/defold/Test;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidJarWithMetaInf(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android"), "This test is only run for Android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/src/TestJar.java"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/JarDep.jar"),
                new FileExtenderResource("test-data/ext/lib/android/meta-inf.jar"));

        File destination = doBuild(sourceFiles, configuration);
        List<String> metaInfFiles = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(destination)) {
            final Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final String entryName = entry.getName();
                if (!entry.isDirectory() && entryName.startsWith("META-INF")) {
                    metaInfFiles.add(entryName);
                }
            }
        }
        List<String> expected = List.of("META-INF/inner.folder/com.inner", "META-INF/inner.folder/io.foo.service.HTTPClient");
        assertTrue(expected.containsAll(metaInfFiles) &&  metaInfFiles.containsAll(expected));
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidRJar(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android") && configuration.version.version.isGreaterThan(1, 2, 174),
            "Defold version does not support Android resources compilation test."
        );

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/extendertest/R;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineAppManifest(TestConfiguration configuration) throws IOException, ExtenderClientException {
        // Testing that using an app.manifest helps resolve issues with duplicate symbols
        // E.g. removing libs, symbols and jar files

        boolean isAndroid = configuration.platform.contains("android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/testproject_appmanifest/_app/app.manifest"),
                new FileExtenderResource("test-data/testproject_appmanifest/ext/ext.manifest"),
                new FileExtenderResource("test-data/testproject_appmanifest/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/testproject_appmanifest/ext/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
                new FileExtenderResource("test-data/testproject_appmanifest/ext2/ext.manifest"),
                new FileExtenderResource("test-data/testproject_appmanifest/ext2/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/testproject_appmanifest/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "blib")))
        );

        if (isAndroid) {
            sourceFiles.add(new FileExtenderResource("test-data/testproject_appmanifest/ext2/lib/android/Dummy1.jar"));
            sourceFiles.add(new FileExtenderResource("test-data/testproject_appmanifest/ext2/lib/android/Dummy2.jar"));
        }

        doBuild(sourceFiles, configuration);
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildLinkWithoutDotLib(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("win32") &&
                (configuration.version.version.isGreaterThan(1, 2, 134) || configuration.version.version.isVersion(0, 0, 0) ),
                "This test was written to test a Win32 link.exe -> clang transition");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext3/ext.manifest"),
                new FileExtenderResource("test-data/ext3/src/extension.cpp")
        );

        doBuild(sourceFiles, configuration);
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineAppManifestVariant(TestConfiguration configuration) throws IOException, ExtenderClientException {
        // Testing that the variant parameter can be parse and processed properly.
        // This test requires that we have a debug.appmanifest present in the SDK and only
        // our test data SDK currently has that, so we can only test it on that version

        if (!configuration.platform.equals("a")) {
            return;
        }

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/testproject_appmanifest_variant/_app/app.manifest"));

        doBuild(sourceFiles, configuration);
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineWithError(TestConfiguration configuration) throws IOException, ExtenderClientException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
            new FileExtenderResource("test-data/ext/ext.manifest"),
            new FileExtenderResource("test-data/ext/include/ext.h"),
            new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
            new FileExtenderResource(String.format("test-data/ext/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
            new FileExtenderResource("test-data/ext_error_extension/ext.manifest"),
            new FileExtenderResource("test-data/ext_error_extension/src/test_error_ext.cpp"),
            new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml")
        );

        assertThrows(ExtenderClientException.class, () -> {
            doBuild(sourceFiles, configuration);
        });
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineWithDynamicLibs(TestConfiguration configuration) throws IOException, ExtenderClientException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
            new FileExtenderResource("test-data/ext_dyn_libs/ext.manifest"),
            new FileExtenderResource("test-data/ext_dyn_libs/src/test_ext.cpp"),
            new FileExtenderResource(String.format("test-data/ext_dyn_libs/lib/%s/%s", configuration.platform, getDynamicLibName(configuration.platform, "dynamic_specific1"))),
            new FileExtenderResource("test-data/ext_dyn_libs2/ext.manifest"),
            new FileExtenderResource("test-data/ext_dyn_libs2/src/extension.cpp"),
            new FileExtenderResource(String.format("test-data/ext_dyn_libs2/lib/%s/%s", configuration.platform, getDynamicLibName(configuration.platform, "dynamic_specific2"))),
            new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml")
        );

        File destination = doBuild(sourceFiles, configuration);

        try (ZipFile zipFile = new ZipFile(destination)) {
            assertNotNull(zipFile.getEntry(getDynamicLibName(configuration.platform, "dynamic_specific1")));
            assertNotNull(zipFile.getEntry(getDynamicLibName(configuration.platform, "dynamic_specific2")));
        }
    }

    @Test
    public void testUnsupportedVersion() throws IOException, ExtenderClientException {
        TestConfiguration configuration = new TestConfiguration(new DefoldVersion("non-exist", new Version(2, 10, 1), new String[]{ "x86_64-linux" }) , "x86_64-linux");
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
            new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
            new FileExtenderResource("test-data/ext2/ext.manifest"),
            new FileExtenderResource("test-data/ext2/src/test_ext.cpp"),
            new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
            new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "blib")))
        );

        ExtenderClientException exc = assertThrows(ExtenderClientException.class, () -> doBuild(sourceFiles, configuration));
        assertTrue(exc.getMessage().contains("Unsupported engine version"));
    }
}
