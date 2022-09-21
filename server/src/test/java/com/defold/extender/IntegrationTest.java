package com.defold.extender;

import com.defold.extender.client.*;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.junit.*;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class IntegrationTest {

    private static final int EXTENDER_PORT = 9000;

    private TestConfiguration configuration;
    private long startTime;

    private static final String DM_PACKAGES_URL = System.getenv("DM_PACKAGES_URL");

    @Rule
    public TestName name = new TestName();

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

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<TestConfiguration> data() {

        boolean ciBuild = System.getenv("GITHUB_WORKSPACE") != null;

        ArrayList<TestConfiguration> data = new ArrayList<>();

        DefoldVersion[] versions = {
                // "a" is a made up sdk where we can more easily test build.yml fixes
                new DefoldVersion("a", new Version(0, 0, 0), new String[] {"armv7-android", "x86_64-win32"} ),

                // At 1.2.185, we updated to iOS 14.5, macOS 11.3 and XCode 12.5
                new DefoldVersion("0a8d3e879724132afb18d47e0040c2034be07504", new Version(1, 2, 185), new String[] {"armv7-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                new DefoldVersion("d393bae6a361f86cf2263ab312c9b3cea45253ab", new Version(1, 2, 191), new String[] {"armv7-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                new DefoldVersion("06bc078e490fd7d94ec01e38abac989f6cc351a5", new Version(1, 3, 1), new String[] {"armv7-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // At 1.3.6 w removed the support for iOS in the base container (minimizing the container size)
                new DefoldVersion("905234d8da2e642f1075c73aaa1bfb72e49199e3", new Version(1, 3, 6), new String[] {"armv7-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),

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
            for (String platform : versions[i].platforms ) {
                data.add(new TestConfiguration(versions[i], platform));
            }
        }

        return data;
    }

    public IntegrationTest(TestConfiguration configuration) {
        this.configuration = configuration;
    }

    @BeforeClass
    public static void beforeClass() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.putEnv("DM_PACKAGES_URL", IntegrationTest.DM_PACKAGES_URL);
        processExecutor.execute("scripts/start-test-server.sh");
        System.out.println(processExecutor.getOutput());

        long startTime = System.currentTimeMillis();

        // Wait for server to start in container.
        File cacheDir = new File("build");
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

    @AfterClass
    public static void afterClass() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.execute("scripts/stop-test-server.sh");
        System.out.println(processExecutor.getOutput());
    }

    @Before
    public void beforeTest() throws IOException {
        File cachedBuild = new File(String.format("build/%s/build.zip", configuration.platform));
        if (cachedBuild.exists())
            cachedBuild.delete();
        assertFalse(cachedBuild.exists());

        startTime = System.currentTimeMillis();
    }

    @After
    public void afterTest()
    {
        File buildDir = new File("build" + File.separator + configuration.version.sha1);
        if (buildDir.exists()) {
            try {
                FileUtils.deleteDirectory(buildDir);
            } catch (IOException e) {
            }
        }

        System.out.println(String.format("Test %s took: %.2f seconds", name.getMethodName(), (System.currentTimeMillis() - startTime) / 1000.f));
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

    private File doBuild(List<ExtenderResource> sourceFiles) throws IOException, ExtenderClientException {
        File cacheDir = new File("build");
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

        assertTrue("Resulting engine should be of a size greater than zero.", destination.length() > 0);
        assertEquals("Log should be of size zero if successful.", 0, log.length());

        ExtenderClientCache cache = new ExtenderClientCache(cacheDir);
        assertTrue(cache.getCachedBuildFile(configuration.platform).exists());

        ZipFile zipFile = new ZipFile(destination);
        String[] expectedEngineNames = getEngineNames(configuration.platform);
        for (String engineName : expectedEngineNames) {
            assertNotEquals(null, zipFile.getEntry( engineName ) );
        }

        if (configuration.platform.endsWith("android")) {
            // Add this when we've made sure that all android builds create a classes.dex
            assertNotEquals(null, zipFile.getEntry("classes.dex"));
        }

        return destination;
    }

    @Test
    public void buildEngine() throws IOException, ExtenderClientException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext2/ext.manifest"),
                new FileExtenderResource("test-data/ext2/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
                new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "blib")))
        );

        doBuild(sourceFiles);
    }

    @Test
    public void buildExtensionStdLib() throws IOException, ExtenderClientException {
        org.junit.Assume.assumeTrue("Only use with real sdk's", !configuration.version.version.isVersion(0, 0, 0));
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext_std/ext.manifest"),
                new FileExtenderResource("test-data/ext_std/include/std.h"),
                new FileExtenderResource("test-data/ext_std/src/test_ext.cpp"),
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource(String.format("test-data/ext_std/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "std")))
        );
        doBuild(sourceFiles);
    }

    @Test
    public void buildEngineWithBaseExtension() throws IOException, ExtenderClientException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/include/ext.h"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
                new FileExtenderResource("test-data/ext_use_base_extension/ext.manifest"),
                new FileExtenderResource("test-data/ext_use_base_extension/src/test_ext.cpp"),
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml")
        );

        doBuild(sourceFiles);
    }

    private boolean checkClassesDexClasses(File buildZip, List<String> classes) throws IOException {
        Set<String> dexClasses = new HashSet<>();

        ZipFile zipFile = new ZipFile(buildZip);
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

        for (String cls : classes) {
            if (!dexClasses.contains(cls)) {
                System.err.println(String.format("Missing class %s", cls));
                return false;
            }
        }
        return true;
    }

    @Test
    public void buildAndroidCheckClassesDex() throws IOException, ExtenderClientException {
        org.junit.Assume.assumeTrue("This test is only run for Android", configuration.platform.contains("android"));

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/Dummy.jar"));

        File destination = doBuild(sourceFiles);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/dummy/Dummy;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @Test
    public void buildAndroidCheckClassesMultiDex() throws IOException, ExtenderClientException {
        org.junit.Assume.assumeTrue("This test is only run for Android", configuration.platform.contains("android"));

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/Dummy.jar"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/VeryLarge1.jar"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/VeryLarge2.jar"));

        File destination = doBuild(sourceFiles);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/dummy/Dummy;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @Test
    public void buildAndroidCheckCompiledJava() throws IOException, ExtenderClientException {
        org.junit.Assume.assumeTrue("This test is only run for Android", configuration.platform.contains("android"));

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/src/Test.java"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/Dummy.jar"));

        File destination = doBuild(sourceFiles);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/dummy/Dummy;", "Lcom/defold/Test;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    /*
     * Test if a Java source can import classes specified in a supplied Jar file.
     */
    @Test
    public void buildAndroidJavaJarDependency() throws IOException, ExtenderClientException {
        org.junit.Assume.assumeTrue("This test is only run for Android", configuration.platform.contains("android"));

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/src/TestJar.java"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/JarDep.jar"));

        File destination = doBuild(sourceFiles);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/JarDep;", "Lcom/defold/Test;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @Test
    public void buildAndroidRJar() throws IOException, ExtenderClientException {

        org.junit.Assume.assumeTrue("Defold version does not support Android resources compilation test.",
                configuration.platform.contains("android") && configuration.version.version.isGreaterThan(1, 2, 174)
        );

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"));

        File destination = doBuild(sourceFiles);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/extendertest/R;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @Test
    public void buildEngineAppManifest() throws IOException, ExtenderClientException {
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

        doBuild(sourceFiles);
    }

    @Test
    public void buildLinkWithoutDotLib() throws IOException, ExtenderClientException {

        org.junit.Assume.assumeTrue("This test was written to test a Win32 link.exe -> clang transition", configuration.platform.contains("win32") &&
                (configuration.version.version.isGreaterThan(1, 2, 134) || configuration.version.version.isVersion(0, 0, 0) ));

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext3/ext.manifest"),
                new FileExtenderResource("test-data/ext3/src/extension.cpp")
        );

        doBuild(sourceFiles);
    }

    @Test
    public void buildEngineAppManifestVariant() throws IOException, ExtenderClientException {
        // Testing that the variant parameter can be parse and processed properly.
        // This test requires that we have a debug.appmanifest present in the SDK and only
        // our test data SDK currently has that, so we can only test it on that version

        if (!configuration.platform.equals("a")) {
            return;
        }

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/testproject_appmanifest_variant/_app/app.manifest"));

        doBuild(sourceFiles);
    }
}
