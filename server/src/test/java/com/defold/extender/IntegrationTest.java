package com.defold.extender;

import com.defold.extender.client.*;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class IntegrationTest {

    private static final int EXTENDER_PORT = 9000;

    private TestConfiguration configuration;

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

        ArrayList<TestConfiguration> data = new ArrayList<>();

        DefoldVersion[] versions = {
                // "a" is a made up sdk where we can more easily test build.yml fixes
                new DefoldVersion("a", new Version(0, 0, 0), new String[] {"x86-osx", "armv7-android"} ),

                new DefoldVersion("8e1d5f8a8a0e1734c9e873ec72b56bea53f25d87", new Version(1, 2, 97), new String[] {"x86-osx"}),
                new DefoldVersion("735ff76c8b1f93b3126ff223cd234d7ceb5b886d", new Version(1, 2, 98), new String[] {"armv7-android", "armv7-ios", "arm64-ios", "x86-osx", "x86_64-osx"}),
                new DefoldVersion("0d7f8b51658bee90cb38f3d651b3ba072394afed", new Version(1, 2, 99), new String[] {"armv7-android", "armv7-ios", "arm64-ios", "x86-osx", "x86_64-osx"}),
                new DefoldVersion("1afccdb2cd42ca3bc7612a0496dfa6d434a8ebf9", new Version(1, 2, 100), new String[] {"armv7-android", "armv7-ios", "arm64-ios", "x86-osx", "x86_64-osx"}),
        };

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
        processExecutor.execute("scripts/start-test-server.sh");
        System.out.println(processExecutor.getOutput());

        // Wait for server to start in container.
        Thread.sleep(7000);
    }

    @AfterClass
    public static void afterClass() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.execute("scripts/stop-test-server.sh");
        System.out.println(processExecutor.getOutput());
    }

    private void clearCache()
    {
        File cachedBuild = new File(String.format("build/%s/build.zip", configuration.platform));
        if (cachedBuild.exists())
            cachedBuild.delete();
        assertFalse(cachedBuild.exists());
    }

    @Test
    public void buildEngineOLD() throws IOException, ExtenderClientException {

        org.junit.Assume.assumeFalse("Too new sdk - skipping", configuration.version.version.isGreaterThan(1, 2, 100) );

        clearCache();

        File cacheDir = new File("build");
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform))
        );
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
        assertTrue(cache.getCachedBuildFile(platform).exists());

        assertTrue("Resulting engine should be of a size greater than zero.", destination.length() > 0);
        assertEquals("Log should be of size zero if successful.", 0, log.length());

        ZipFile zipFile = new ZipFile(destination);

        if (platform.endsWith("android")) {
            assertNotEquals(null, zipFile.getEntry("libdmengine.so"));
        }
        else if (platform.endsWith("ios") || platform.endsWith("osx")) {
            assertNotEquals(null, zipFile.getEntry("dmengine"));
        }

        FileUtils.deleteDirectory(new File("build" + File.separator + sdkVersion));
    }

    @Test
    public void buildEngine() throws IOException, ExtenderClientException {

        boolean isAndroid = configuration.platform.contains("android");
        // The bug in question is related to library dependency order. (i.e. getting "undefined reference to X")
        boolean hasAndroidBug = isAndroid && (configuration.version.version.isGreaterThan(0, 0, 0) && configuration.version.version.isLessThan(1, 2, 101) );

        org.junit.Assume.assumeFalse("Has android bug - skipping", hasAndroidBug );

        clearCache();

        File cacheDir = new File("build");
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext2/ext.manifest"),
                new FileExtenderResource("test-data/ext2/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext2/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource(String.format("test-data/ext2/lib/%s/libblib.a", configuration.platform))
        );
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
        assertTrue(cache.getCachedBuildFile(platform).exists());

        assertTrue("Resulting engine should be of a size greater than zero.", destination.length() > 0);
        assertEquals("Log should be of size zero if successful.", 0, log.length());

        ZipFile zipFile = new ZipFile(destination);

        if (platform.endsWith("android")) {
            /* Add this when we've made sure that all android builds create a classes.dex
            if (configuration.runTestClassesDex) {
                assertNotEquals(null, zipFile.getEntry("classes.dex"));
            }*/
            assertNotEquals(null, zipFile.getEntry("libdmengine.so"));
        }
        else if (platform.endsWith("ios") || platform.endsWith("osx")) {
            assertNotEquals(null, zipFile.getEntry("dmengine"));
        }

        FileUtils.deleteDirectory(new File("build" + File.separator + sdkVersion));
    }

    @Test
    public void buildAndroidCheckClassesDex() throws IOException, ExtenderClientException, InterruptedException {

        org.junit.Assume.assumeTrue("Defold version does not support classes.dex test.", configuration.platform.contains("android") && configuration.version.version.isVersion(0, 0, 0) );

        clearCache();

        File cacheDir = new File("build");
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/Dummy.jar"));
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
            System.out.println(new String(Files.readAllBytes(log.toPath())));
            throw e;
        }

        assertTrue("Resulting engine should be of a size greater than zero.", destination.length() > 0);
        assertEquals("Log should be of size zero if successful.", 0, log.length());

        ZipFile zipFile = new ZipFile(destination);
        ZipEntry classesDexEntry = zipFile.getEntry("classes.dex");
        assertNotEquals(null, classesDexEntry);
        assertNotEquals(null, zipFile.getEntry("libdmengine.so"));


        InputStream in = zipFile.getInputStream(classesDexEntry);
        Path tmpClassesDexPath = Files.createTempFile("classes", "dex");
        Files.copy(in, tmpClassesDexPath, StandardCopyOption.REPLACE_EXISTING);

        // Verify that classes.dex contains our Dummy class
        DexFile dexFile = DexFileFactory.loadDexFile(tmpClassesDexPath.toFile().getAbsolutePath(), 19 ); // api level

        List<String> expected = new ArrayList<>();
        expected.add("Lcom/svenandersson/dummy/Dummy;");
        expected.add("Lcom/defoldtest/engine/Engine;");

        for (ClassDef classDef: dexFile.getClasses()) {
            assertTrue( expected.contains( classDef.getType() ) );
        }
    }

    @Test
    public void buildEngineAppManifest() throws IOException, ExtenderClientException {
        // Testing that using an app.manifest helps resolve issues with duplicate symbols
        // E.g. removing libs, symbols and jar files

        boolean isAndroid = configuration.platform.contains("android");

        clearCache();

        File cacheDir = new File("build");
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/testproject_appmanifest/_app/app.manifest"),
                new FileExtenderResource("test-data/testproject_appmanifest/ext/ext.manifest"),
                new FileExtenderResource("test-data/testproject_appmanifest/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/testproject_appmanifest/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/testproject_appmanifest/ext2/ext.manifest"),
                new FileExtenderResource("test-data/testproject_appmanifest/ext2/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/testproject_appmanifest/ext2/lib/%s/libblib.a", configuration.platform))
        );

        if (isAndroid) {
            sourceFiles.add(new FileExtenderResource("test-data/testproject_appmanifest/ext2/lib/armv7-android/Dummy1.jar"));
            sourceFiles.add(new FileExtenderResource("test-data/testproject_appmanifest/ext2/lib/armv7-android/Dummy2.jar"));
        }

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

        FileUtils.deleteDirectory(new File("build" + File.separator + sdkVersion));
    }
}
