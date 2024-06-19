package com.defold.extender;

import com.defold.extender.client.*;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.junit.*;
import org.junit.rules.TestName;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.*;

public class AuthenticationTest {

    private static final int EXTENDER_PORT = 9000;
    private static final String SDK_VERSION = "e624625d90111ab8442e6b672b1335bb024b9885"; // 1.4.3
    private static final String PLATFORM_ARMV7_ANDROID = "armv7-android";
    private static final String PLATFORM_LINUX = "x86_64-linux";

    private long startTime;

    private static final List<ExtenderResource> SOURCE_FILES = Lists.newArrayList(
            new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
            new FileExtenderResource("test-data/ext_basic/ext.manifest"),
            new FileExtenderResource("test-data/ext_basic/src/test_ext.cpp")
    );

    static {
        LoggingSystem.get(ClassLoader.getSystemClassLoader()).setLogLevel(Logger.ROOT_LOGGER_NAME, LogLevel.INFO);
    }

    @Rule
    public TestName name = new TestName();

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    public AuthenticationTest() {}

    @BeforeClass
    public static void beforeClass() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.putEnv("COMPOSE_PROFILE", "auth-test");
        processExecutor.putEnv("APPLICATION", "extender-test-auth");
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
        processExecutor.putEnv("APPLICATION", "extender-test-auth");
        processExecutor.execute("scripts/stop-test-server.sh");
        System.out.println(processExecutor.getOutput());
    }

    @Before
    public void beforeTest() throws IOException {
        startTime = System.currentTimeMillis();
    }

    @After
    public void afterTest()
    {
        File buildDir = new File("build" + File.separator + SDK_VERSION);
        if (buildDir.exists()) {
            try {
                FileUtils.deleteDirectory(buildDir);
            } catch (IOException e) {
            }
        }

        System.out.println(String.format("Test %s took: %.2f seconds", name.getMethodName(), (System.currentTimeMillis() - startTime) / 1000.f));
    }

    private void doBuild(String user, String password, File destination, File log, String platform) throws IOException, ExtenderClientException {
        File cachedBuild = new File(String.format("build/%s/build.zip", platform));
        if (cachedBuild.exists())
            cachedBuild.delete();
        assertFalse(cachedBuild.exists());

        File cacheDir = new File("build");
        String url;
        if (user != null) {
            url = String.format("http://%s:%s@localhost:%d", user, password, EXTENDER_PORT);
        }
        else {
            url = String.format("http://localhost:%d", EXTENDER_PORT);
        }
        System.out.println("URL " + url);
        ExtenderClient extenderClient = new ExtenderClient(url, cacheDir);
        try {
            extenderClient.build(
                    platform,
                    SDK_VERSION,
                    SOURCE_FILES,
                    destination,
                    log
            );
        } catch (ExtenderClientException e) {
            System.out.println("ERROR LOG:");
            System.out.println(new String(Files.readAllBytes(log.toPath())));
            throw e;
        }
    }

    @Test
    public void buildLinuxWithAuthenticatedUser() throws IOException, ExtenderClientException {
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();
        doBuild("bobuser", "bobpassword", destination, log, PLATFORM_LINUX);
        assertTrue("Resulting engine should be of a size greater than zero.", destination.length() > 0);
        assertEquals("Log should be of size zero if successful.", 0, log.length());
    }

    @Test
    public void buildLinuxWithWrongPassword() throws IOException, ExtenderClientException {
        exceptionRule.expect(ExtenderClientException.class);

        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();
        doBuild("bobuser", "wrongpassword", destination, log, PLATFORM_LINUX);
        assertTrue("Resulting engine should be of a size equal to zero.", destination.length() == 0);
    }

    @Test
    public void buildLinuxWithNoUser() throws IOException, ExtenderClientException {
        exceptionRule.expect(ExtenderClientException.class);

        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();
        doBuild(null, null, destination, log, PLATFORM_LINUX);
        assertTrue("Resulting engine should be of a size equal to zero.", destination.length() == 0);
    }

    @Test
    public void buildAndroidWithNoUser() throws IOException, ExtenderClientException {
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();
        doBuild(null, null, destination, log, PLATFORM_ARMV7_ANDROID);
        assertTrue("Resulting engine should be of a size greater than zero.", destination.length() > 0);
    }
}
