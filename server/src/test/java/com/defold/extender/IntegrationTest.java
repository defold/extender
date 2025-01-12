package com.defold.extender;

import com.defold.extender.client.*;
import com.google.common.collect.Lists;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        public boolean async = false;

        public TestConfiguration(DefoldVersion version, String platform, boolean async) {
            this.version = version;
            this.platform = platform;
            this.async = async;
        }

        @Override
        public String toString() {
            return String.format("%s sha1(%s) %s async: %b", version.version.toString(), version.sha1, platform, async);
        }
    }

    public static List<TestConfiguration> data() {

        boolean ciBuild = System.getenv("GITHUB_WORKSPACE") != null;

        ArrayList<TestConfiguration> data = new ArrayList<>();

        DefoldVersion[] versions = {
                // "a" is a made up sdk where we can more easily test build.yml fixes
                // new DefoldVersion("a", new Version(0, 0, 0), new String[] {"armv7-android", "x86_64-win32"} ),

                // // 2024-08-20 https://github.com/defold/defold/releases/tag/1.9.1
                new DefoldVersion("691478c02875b80e76da65d2f5756394e7a906b1", new Version(1, 9, 1), new String[] {"armv7-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // 2024-09-16 https://github.com/defold/defold/releases/tag/1.9.2
                // new DefoldVersion("3251ca82359cf238a1074e383281e3126547d50b", new Version(1, 9, 2), new String[] {"armv7-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // //  2024-10-14 https://github.com/defold/defold/releases/tag/1.9.3
                new DefoldVersion("e4aaff11f49c941fde1dd93883cf69c6b8abebe4", new Version(1, 9, 3), new String[] {"armv7-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // 2024-10-29 https://github.com/defold/defold/releases/tag/1.9.4
                // new DefoldVersion("edfdbe31830c1f8aa4d96644569ae87a8ea32672", new Version(1, 9, 4), new String[] {"armv7-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // 2024-12-05 https://github.com/defold/defold/releases/tag/1.9.5
                new DefoldVersion("d01194cf0fb576b516a1dca6af6f643e9e590051", new Version(1, 9, 5), new String[] {"armv7-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // 2024-12-19 https://github.com/defold/defold/releases/tag/1.9.6
                new DefoldVersion("11d2cd3a9be17b2fc5a2cb5cea59bbfb4af1ca96", new Version(1, 9, 6), new String[] {"armv7-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),

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
                data.add(new TestConfiguration(versions[i], platform, false));
                data.add(new TestConfiguration(versions[i], platform, true));
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

    private File doBuild(List<ExtenderResource> sourceFiles, TestConfiguration configuration) throws IOException, ExtenderClientException {
        File cacheDir = Files.createTempDirectory(String.format("%s-%s", configuration.platform, configuration.version.toString())).toFile();
        cacheDir.deleteOnExit();
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        String platform = configuration.platform;
        String sdkVersion = configuration.version.sha1;
        boolean isAsync = configuration.async;

        try {
            extenderClient.build(
                    platform,
                    sdkVersion,
                    sourceFiles,
                    destination,
                    log,
                    isAsync
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
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/Dummy.jar"));

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
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/Dummy.jar"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/VeryLarge1.jar"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/VeryLarge2.jar"));

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
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/Dummy.jar"));

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
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/JarDep.jar"));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/JarDep;", "Lcom/defold/Test;"});
        assertTrue(checkClassesDexClasses(destination, classes));
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
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"));

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
            sourceFiles.add(new FileExtenderResource("test-data/testproject_appmanifest/ext2/lib/armv7-android/Dummy1.jar"));
            sourceFiles.add(new FileExtenderResource("test-data/testproject_appmanifest/ext2/lib/armv7-android/Dummy2.jar"));
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
}
