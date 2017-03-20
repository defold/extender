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

    private static class TestConfiguration {
        public String defoldSha1 = "";
        public String platform = "";
        public boolean runTestClassesDex = true;

        public TestConfiguration(String defoldSha1, String platform, boolean runTestClassesDex) {
            this.defoldSha1 = defoldSha1;
            this.platform = platform;
            this.runTestClassesDex = runTestClassesDex;
        }

        @Override
        public String toString() {
            return String.format("sha1(%s) %s", defoldSha1, platform);
        }
    }

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<TestConfiguration> data() {

        ArrayList<TestConfiguration> data = new ArrayList<>();

        String[] versions = {
                "8e1d5f8a8a0e1734c9e873ec72b56bea53f25d87", // 1.2.97
                "735ff76c8b1f93b3126ff223cd234d7ceb5b886d", // 1.2.98
                "0d7f8b51658bee90cb38f3d651b3ba072394afed", // 1.2.99
        };

        String[][] supportedPlatforms = new String[][] {
                new String[] {"x86-osx"},
                new String[] {"armv7-android", "armv7-ios", "arm64-ios", "x86-osx", "x86_64-osx"},
                new String[] {"armv7-android", "armv7-ios", "arm64-ios", "x86-osx", "x86_64-osx"},
        };

        data.add(new TestConfiguration("a", "armv7-android", true));

        for( int i = 0; i < versions.length; ++i )
        {
            String version = versions[i];

            for (String platform : supportedPlatforms[i]) {
                data.add(new TestConfiguration(version, platform, false));
            }
        }

        return data;
    }

    public IntegrationTest(TestConfiguration configuration) {
        this.configuration = configuration;
    }

    @BeforeClass
    public static void before() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.execute("scripts/start-test-server.sh");
        System.out.println(processExecutor.getOutput());

        // Wait for server to start in container.
        Thread.sleep(7000);
    }

    @AfterClass
    public static void after() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.execute("scripts/stop-test-server.sh");
        System.out.println(processExecutor.getOutput());
    }

    @Test
    public void buildingRemoteShouldReturnEngine() throws IOException, ExtenderClientException {

        org.junit.Assume.assumeTrue("Dummy Defold version - skipping", configuration.defoldSha1.length() > 1);

        File cachedBuild = new File(String.format("build/%s/build.zip", configuration.platform));
        if (cachedBuild.exists())
            cachedBuild.delete();
        assertFalse(cachedBuild.exists());

        File cacheDir = new File("build");
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/include/test_ext.h"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libblib.a", configuration.platform)),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform))
        );
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        String platform = configuration.platform;
        String sdkVersion = configuration.defoldSha1;

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
            if (configuration.runTestClassesDex) {
                assertNotEquals(null, zipFile.getEntry("classes.dex"));
            }
            assertNotEquals(null, zipFile.getEntry("libdmengine.so"));
        }
        else if (platform.endsWith("ios") || platform.endsWith("osx")) {
            assertNotEquals(null, zipFile.getEntry("dmengine"));
        }

        FileUtils.deleteDirectory(new File("build" + File.separator + sdkVersion));
    }

    @Test
    public void buildAndroidCheckClassesDex() throws IOException, ExtenderClientException, InterruptedException {

        org.junit.Assume.assumeTrue("Defold version does not support classes.dex test.", configuration.runTestClassesDex);

        File cacheDir = new File("build");
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/include/test_ext.h"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/libalib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/libblib.a"),
                new FileExtenderResource("test-data/ext/lib/armv7-android/Dummy.jar"));
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        String platform = configuration.platform;
        String sdkVersion = configuration.defoldSha1;

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
        for (ClassDef classDef: dexFile.getClasses()) {
            assertEquals("Lcom/svenandersson/dummy/Dummy;", classDef.getType());
        }
    }
}
