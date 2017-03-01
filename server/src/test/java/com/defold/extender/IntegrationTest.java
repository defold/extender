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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class IntegrationTest {

    private static final int EXTENDER_PORT = 9000;

    private TestConfiguration configuration;

    private static class TestConfiguration {
        public String defoldSha1 = "";
        public boolean runTestClassesDex = true;

        public TestConfiguration(String defoldSha1, boolean runTestClassesDex) {
            this.defoldSha1 = defoldSha1;
            this.runTestClassesDex = runTestClassesDex;
        }

        @Override
        public String toString() {
            return defoldSha1;
        }
    }

    @Parameterized.Parameters(name = "{index}: sha1({0})")
    public static Collection<TestConfiguration> data() {
        TestConfiguration[] data = new TestConfiguration[] {
                new TestConfiguration(/* local */  "a", true),
                new TestConfiguration(/* 1.2.97 */ "8e1d5f8a8a0e1734c9e873ec72b56bea53f25d87", false),
                new TestConfiguration(/* 1.2.98 */ "735ff76c8b1f93b3126ff223cd234d7ceb5b886d", false)
        };
        return Arrays.asList(data);
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
        File cacheDir = new File("build");
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/include/test_ext.h"),
                new FileExtenderResource("test-data/ext/lib/x86-osx/libalib.a"));
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        String platform = "x86-osx";
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

        ExtenderClientCache cache = new ExtenderClientCache(cacheDir);
        assertTrue(cache.getCachedBuildFile(platform).exists());

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
                new FileExtenderResource("test-data/ext/lib/armv7-android/Dummy.jar"));
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        String platform = "armv7-android";
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
        DexFile dexFile = DexFileFactory.loadDexFile(tmpClassesDexPath.toFile().getAbsolutePath(), 19 /*api level*/);
        for (ClassDef classDef: dexFile.getClasses()) {
            assertEquals("Lcom/svenandersson/dummy/Dummy;", classDef.getType());
        }
    }
}
