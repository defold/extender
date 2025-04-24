package com.defold.extender;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.ExtenderClientException;
import com.defold.extender.client.ExtenderResource;
import com.defold.extender.client.FileExtenderResource;
import com.defold.extender.process.ProcessExecutor;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

public class AuthenticationTest implements AfterEachCallback {

    private static final int EXTENDER_PORT = 9001;
    private static final String SDK_VERSION = "d6882f432beca85d460ec42497888157c356d058"; // 1.9.0
    private static final String PLATFORM_ARMV7_ANDROID = "armv7-android";
    private static final String PLATFORM_LINUX = "x86_64-linux";

    private static final List<ExtenderResource> SOURCE_FILES = Lists.newArrayList(
            new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
            new FileExtenderResource("test-data/ext_basic/ext.manifest"),
            new FileExtenderResource("test-data/ext_basic/src/test_ext.cpp")
    );

    static {
        LoggingSystem.get(ClassLoader.getSystemClassLoader()).setLogLevel(Logger.ROOT_LOGGER_NAME, LogLevel.INFO);
    }

    public AuthenticationTest() {}

    @BeforeAll
    public static void beforeClass() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.putEnv("COMPOSE_PROFILE", "auth-test");
        processExecutor.putEnv("APPLICATION", "extender-test-auth");
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
        processExecutor.putEnv("APPLICATION", "extender-test-auth");
        processExecutor.execute("scripts/stop-test-server.sh");
        System.out.println(processExecutor.getOutput());
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        File buildDir = new File("build" + File.separator + SDK_VERSION);
        if (buildDir.exists()) {
            try {
                FileUtils.deleteDirectory(buildDir);
            } catch (IOException e) {
            }
        }
    }

    private void doBuild(String user, String password, File destination, File log, String platform) throws IOException, ExtenderClientException {
        File cacheDir = Files.createTempDirectory(platform).toFile();
        cacheDir.deleteOnExit();
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
            // make a copy because several builds can happened concurrenlty. During the build extender client
            // sort source list to calculate hash
            List<ExtenderResource> sourcesCopy = SOURCE_FILES.stream()
                                         .collect(Collectors.toList());
            extenderClient.build(
                    platform,
                    SDK_VERSION,
                    sourcesCopy,
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
        assertTrue(destination.length() > 0, "Resulting engine should be of a size greater than zero.");
        assertEquals(0, log.length(), "Log should be of size zero if successful.");
    }

    @Test
    public void buildLinuxWithWrongPassword() throws IOException, ExtenderClientException {
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();
        assertThrows(ExtenderClientException.class, () -> {
            doBuild("bobuser", "wrongpassword", destination, log, PLATFORM_LINUX);
        });
        assertTrue(destination.length() == 0, "Resulting engine should be of a size equal to zero.");
    }

    @Test
    public void buildLinuxWithNoUser() throws IOException, ExtenderClientException {
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();
        assertThrows(ExtenderClientException.class, () -> {
            doBuild(null, null, destination, log, PLATFORM_LINUX);
        });
        assertTrue(destination.length() == 0, "Resulting engine should be of a size equal to zero.");
    }

    @Test
    public void buildAndroidWithNoUser() throws IOException, ExtenderClientException {
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();
        doBuild(null, null, destination, log, PLATFORM_ARMV7_ANDROID);
        assertTrue(destination.length() > 0, "Resulting engine should be of a size greater than zero.");
    }
}
