package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.services.data.DefoldSdk;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.json.simple.parser.ParseException;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class DefoldSDKServiceTest {
    private static DefoldSdkServiceConfiguration configuration;
    private static DefoldSdkServiceConfiguration zeroCacheConfiguration;
    private static DefoldSdkServiceConfiguration otherLocationConfiguration;

    private static WireMockServer mockServer;
    private static int serverPort = 8090;
    private static Path tmpHTTPRoot = Path.of("/tmp/__defoldsdk_http");

    @BeforeAll
    public static void beforeAll() throws IOException {
        DefoldSDKServiceTest.configuration = DefoldSdkServiceConfiguration.builder()
            .location(Path.of("/tmp/defoldsdk"))
            .sdkUrls(new String[]{"http://d.defold.com/archive/stable/%s/engine/defoldsdk.zip", "http://d.defold.com/archive/%s/engine/defoldsdk.zip"})
            .mappingsUrls(new String[] {"http://d.defold.com/archive/stable/%s/engine/platform.sdks.json", "http://d.defold.com/archive/%s/engine/platform.sdks.json"})
            .cacheSize(3)
            .mappingsCacheSize(3)
            .cacheClearOnExit(true)
            .enableSdkVerification(false)
            .maxVerificationRetryCount(3)
            .build();

        DefoldSDKServiceTest.zeroCacheConfiguration = new DefoldSdkServiceConfiguration(DefoldSDKServiceTest.configuration.toBuilder());
            zeroCacheConfiguration.setCacheSize(0);
    
        Files.createDirectories(DefoldSDKServiceTest.configuration.getLocation());

        DefoldSDKServiceTest.otherLocationConfiguration = new DefoldSdkServiceConfiguration(DefoldSDKServiceTest.zeroCacheConfiguration.toBuilder());
        DefoldSDKServiceTest.otherLocationConfiguration.setLocation(Path.of("/tmp/defoldsdk_test"));
        Files.createDirectories(DefoldSDKServiceTest.otherLocationConfiguration.getLocation());

        // prepare content for serving
        Files.createDirectories(tmpHTTPRoot);
        FileUtils.copyDirectory(new File("test-data/checksum_sdk/"), new File(tmpHTTPRoot.toFile(), "__files"), File::isFile);

        // Configure WireMock to respond with the contents of a local file
        DefoldSDKServiceTest.mockServer = new WireMockServer(WireMockConfiguration.options()
            .port(serverPort)
            .withRootDirectory(tmpHTTPRoot.toString())
        );
        DefoldSDKServiceTest.mockServer.start();
        WireMock.configureFor("localhost", serverPort);

        stubFor(head(urlEqualTo("/test_sdk.zip"))
                .willReturn(aResponse()
                        .withStatus(200)));
        stubFor(get(urlEqualTo("/test_sdk.zip"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBodyFile("test_sdk.zip")
                        .withHeader("Content-Type", "application/zip")));
        stubFor(get(urlEqualTo("/test_sdk.sha256"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBodyFile("test_sdk.sha256")
                        .withHeader("Content-Type", "text/plain")));
        // stub for invalid checksums
        stubFor(head(urlEqualTo("/test_sdk_invalid.zip"))
                .willReturn(aResponse()
                        .withStatus(200)));
        stubFor(get(urlEqualTo("/test_sdk_invalid.zip"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBodyFile("test_sdk.zip")
                        .withHeader("Content-Type", "application/zip")));
        stubFor(get(urlEqualTo("/test_sdk_invalid.sha256"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBodyFile("test_sdk_invalid.sha256")
                        .withHeader("Content-Type", "text/plain")));

    }

    @AfterAll
    public static void afterAll() throws IOException {
        FileUtils.deleteDirectory(DefoldSDKServiceTest.configuration.getLocation().toFile());
        FileUtils.deleteDirectory(DefoldSDKServiceTest.otherLocationConfiguration.getLocation().toFile());

        if (DefoldSDKServiceTest.mockServer != null) {
            DefoldSDKServiceTest.mockServer.stop();
        }
        FileUtils.deleteDirectory(tmpHTTPRoot.toFile());
    }

    @Test
    @Disabled("SDK too large to download on every test round.")
    public void t() throws IOException, ExtenderException {
        DefoldSdkService defoldSdkService = new DefoldSdkService(DefoldSDKServiceTest.configuration, new SimpleMeterRegistry());
        DefoldSdk sdk = defoldSdkService.getSdk("f7778a8f59ef2a8dda5d445f471368e8bd1cb1ac");
        System.out.println(sdk.toFile().getCanonicalFile());
    }

    @Test
    @Disabled("SDK too large to download on every test round.")
    public void onlyStoreTheNewest() throws IOException, ExtenderException {
        DefoldSdkService defoldSdkService = new DefoldSdkService(DefoldSDKServiceTest.configuration, new SimpleMeterRegistry());

        String[] sdksToDownload = {
                "fe2b689302e79b7cf8c0bc7d934f23587b268c8a",
                "8f3e864464062e1b35c207521dc65dfd77899cdf",
                "e41438cca6cc1550d4a0131b8fc3858c2a4097f1",
                "7107bc8781535e83cbb30734b32d6b32a3039cd0",
                "f7778a8f59ef2a8dda5d445f471368e8bd1cb1ac"};

        // Download all SDK:s
        for (String sdkHash : sdksToDownload) {
            defoldSdkService.getSdk(sdkHash);
        }

        List<String> collect = Files.list(DefoldSDKServiceTest.configuration.getLocation()).map(path -> path.toFile().getName()).collect(Collectors.toList());

        assertEquals(DefoldSDKServiceTest.configuration.getCacheSize(), collect.size());
        assertTrue(collect.contains("e41438cca6cc1550d4a0131b8fc3858c2a4097f1"));
        assertTrue(collect.contains("7107bc8781535e83cbb30734b32d6b32a3039cd0"));
        assertTrue(collect.contains("f7778a8f59ef2a8dda5d445f471368e8bd1cb1ac"));
    }

    @Test
    public void testGetSDK() throws IOException, ExtenderException {
        DefoldSdkService defoldSdkService = new DefoldSdkService(DefoldSDKServiceTest.configuration, new SimpleMeterRegistry());

        File dir = new File(DefoldSDKServiceTest.configuration.getLocation().toFile(), "notexist");
        assertFalse(Files.exists(dir.toPath()));

        assertThrows(ExtenderException.class, () -> defoldSdkService.getSdk("notexist"));
    }

    @Test
    public void testGetSDKRefCount() throws IOException, ExtenderException, InterruptedException {
        final String testSdk = "11d2cd3a9be17b2fc5a2cb5cea59bbfb4af1ca96";
        final int expectedRefCount = 3;
        List<DefoldSdk> sdks = new ArrayList<>();
        DefoldSdkService defoldSdkService = new DefoldSdkService(DefoldSDKServiceTest.zeroCacheConfiguration, new SimpleMeterRegistry());

        // check when several threads request one sdk and that sdk need to be downloaded
        ExecutorService service = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(expectedRefCount);
        for (int i = 0; i < expectedRefCount; ++i) {
            service.submit(() -> {
                try {
                    sdks.add(defoldSdkService.getSdk(testSdk));
                } catch (ExtenderException e) {
                    e.printStackTrace();
                }
                latch.countDown();
            });
        }
        latch.await();
        assertEquals(expectedRefCount, defoldSdkService.getSdkRefCount(testSdk));

        // check acquisition with several thread already downloaded sdk
        CountDownLatch latch2 = new CountDownLatch(expectedRefCount);
        for (int i = 0; i < expectedRefCount; ++i) {
            service.submit(() -> {
                try {
                    sdks.add(defoldSdkService.getSdk(testSdk));
                } catch (ExtenderException e) {
                    e.printStackTrace();
                }
                latch2.countDown();
            });
        }
        latch2.await();
        assertEquals(expectedRefCount * 2, defoldSdkService.getSdkRefCount(testSdk));
        // check acquisition in sequence
        for (int i = 0; i < expectedRefCount; ++i) {
            sdks.add(defoldSdkService.getSdk(testSdk));
        }
        assertEquals(expectedRefCount * 3, defoldSdkService.getSdkRefCount(testSdk));
        for (int i = 0; i < expectedRefCount * 3; ++i) {
            sdks.get(i).close();
        }
        assertEquals(0, defoldSdkService.getSdkRefCount(testSdk));

        try (DefoldSdk sdk = defoldSdkService.getSdk(testSdk)) {
            throw new Exception("Something happened");
        } catch (Exception exc) {}

        assertEquals(0, defoldSdkService.getSdkRefCount(testSdk));
        try (DefoldSdk sdk = defoldSdkService.getSdk(testSdk)) {
            System.out.println("Normal return from scoped resource");
        }

        assertEquals(0, defoldSdkService.getSdkRefCount(testSdk));

        defoldSdkService.evictCache();
        assertFalse(new File("/tmp/defoldsdk", testSdk).exists());
    }

    @Test
    public void testSdkCorrectPath() throws IOException, ExtenderException {
        final String testSdk = "11d2cd3a9be17b2fc5a2cb5cea59bbfb4af1ca96";
        DefoldSdkService defoldSdkService = new DefoldSdkService(DefoldSDKServiceTest.otherLocationConfiguration, new SimpleMeterRegistry());
        try (DefoldSdk sdk = defoldSdkService.getSdk(testSdk)) {
            assertTrue(new File(String.format("%s/extender/build.yml", sdk.toFile().getAbsolutePath())).exists());
        }

        defoldSdkService.evictCache();
        assertFalse(new File(DefoldSDKServiceTest.otherLocationConfiguration.getLocation().toFile(), testSdk).exists());
    }

    @Test
    public void testMappingsCacheSize() throws IOException, ExtenderException, ParseException {
        String[] mappingsToDownload = {
            "691478c02875b80e76da65d2f5756394e7a906b1",
            "e4aaff11f49c941fde1dd93883cf69c6b8abebe4",
            "3251ca82359cf238a1074e383281e3126547d50b",
            "edfdbe31830c1f8aa4d96644569ae87a8ea32672",
            "d01194cf0fb576b516a1dca6af6f643e9e590051"};

        DefoldSdkService defoldSdkService = new DefoldSdkService(DefoldSDKServiceTest.zeroCacheConfiguration, new SimpleMeterRegistry());
        for (String hash : mappingsToDownload) {
            defoldSdkService.getPlatformSdkMappings(hash);
        }
        assertEquals(DefoldSDKServiceTest.zeroCacheConfiguration.getMappingsCacheSize(), defoldSdkService.mappingsCache.size());
        String expectedHashes[] = {
            "3251ca82359cf238a1074e383281e3126547d50b",
            "edfdbe31830c1f8aa4d96644569ae87a8ea32672",
            "d01194cf0fb576b516a1dca6af6f643e9e590051"
        };
        for (String hash : expectedHashes) {
            assertTrue(defoldSdkService.mappingsCache.containsKey(hash));
        }
    }

    @Test
    public void testNonExistMappings() throws IOException {
        DefoldSdkService defoldSdkService = new DefoldSdkService(DefoldSDKServiceTest.zeroCacheConfiguration, new SimpleMeterRegistry());
        assertThrows(ExtenderException.class, () -> defoldSdkService.getPlatformSdkMappings("non-exist"));
    }

    @Test
    public void testConcurrentMappingsDownloading() throws IOException, InterruptedException {
        String[] mappingsToDownload = {
            "691478c02875b80e76da65d2f5756394e7a906b1",
            "691478c02875b80e76da65d2f5756394e7a906b1",
            "691478c02875b80e76da65d2f5756394e7a906b1",
            "691478c02875b80e76da65d2f5756394e7a906b1",
            "e4aaff11f49c941fde1dd93883cf69c6b8abebe4",
            "3251ca82359cf238a1074e383281e3126547d50b",
            "691478c02875b80e76da65d2f5756394e7a906b1",
            "edfdbe31830c1f8aa4d96644569ae87a8ea32672",
            "d01194cf0fb576b516a1dca6af6f643e9e590051"};
        DefoldSdkService defoldSdkService = new DefoldSdkService(DefoldSDKServiceTest.zeroCacheConfiguration, new SimpleMeterRegistry());
        ExecutorService service = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(mappingsToDownload.length);
        for (final String hash : mappingsToDownload) {
            service.submit(() -> {
                try {
                    defoldSdkService.getPlatformSdkMappings(hash);
                } catch (ExtenderException|IOException|ParseException e) {
                    e.printStackTrace();
                }
                latch.countDown();
            });
        }
        latch.await();
        assertEquals(DefoldSDKServiceTest.zeroCacheConfiguration.getMappingsCacheSize(), defoldSdkService.mappingsCache.size());
        for (Map.Entry<String, JSONObject> entry : defoldSdkService.mappingsCache.entrySet()) {
            assertNotNull(entry.getValue());
        }
    }

    @Test
    public void testChecksumVerification() throws IOException {
        DefoldSdkServiceConfiguration conf = DefoldSdkServiceConfiguration.builder()
            .location(Path.of("/tmp/defoldsdk"))
            .cacheSize(1)
            .sdkUrls(new String[] {"http://localhost:8090/%s.zip"})
            .enableSdkVerification(true)
            .maxVerificationRetryCount(3)
            .build();
        DefoldSdkService sdkService = new DefoldSdkService(conf, new SimpleMeterRegistry());
        assertDoesNotThrow(() -> sdkService.getSdk("test_sdk"));
    }

    @Test
    public void testInvalidVerification() throws IOException {
        DefoldSdkServiceConfiguration disabledVerificationConf = DefoldSdkServiceConfiguration.builder()
            .location(Path.of("/tmp/defoldsdk"))
            .cacheSize(0)
            .sdkUrls(new String[] {"http://localhost:8090/%s.zip"})
            .enableSdkVerification(false)
            .maxVerificationRetryCount(3)
            .build();
        DefoldSdkService sdkService = new DefoldSdkService(disabledVerificationConf, new SimpleMeterRegistry());
        assertDoesNotThrow(() -> sdkService.getSdk("test_sdk_invalid"));

        DefoldSdkServiceConfiguration enabledVerificationConf = DefoldSdkServiceConfiguration.builder()
            .location(Path.of("/tmp/defoldsdk"))
            .cacheSize(0)
            .sdkUrls(new String[] {"http://localhost:8090/%s.zip"})
            .enableSdkVerification(true)
            .maxVerificationRetryCount(3)
            .build();
        DefoldSdkService sdkService1 = new DefoldSdkService(enabledVerificationConf, new SimpleMeterRegistry());
        // no exception because sdk folder already exists
        assertDoesNotThrow(() -> sdkService.getSdk("test_sdk_invalid"));
        // force remove cache
        sdkService1.evictCache();

        ExtenderException exc = assertThrows(ExtenderException.class, () -> sdkService1.getSdk("test_sdk_invalid"));
        assertTrue(exc.getMessage().contains("Sdk verification failed"));
    }
}
