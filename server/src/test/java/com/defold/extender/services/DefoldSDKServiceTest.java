package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.PlatformNotSupportedException;
import com.defold.extender.VersionNotSupportedException;
import com.defold.extender.services.data.DefoldSdk;
import com.defold.extender.services.data.ResolvedSdk;
import com.defold.extender.services.data.SdkSelection;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class DefoldSDKServiceTest {
    private static final String HASH = "same-engine-sha";
    private static final String LINUX = "x86_64-linux";
    private static final String SWITCH = "arm64-nx64";
    private static final String ANDROID = "arm64-android";
    private static final String PUBLIC_MAPPING = "{\"x86_64-linux\":[\"linux\",\"latest\"],\"arm64-android\":[\"android\",\"ndk25\"]}";
    private static final String SWITCH_MAPPING = "{\"arm64-nx64\":[\"nssdk\",\"2143\"],\"x86_64-linux\":[\"linux\",\"other\"]}";

    @TempDir Path sdkLocation;
    private WireMockServer server;
    private DefoldSdkServiceConfiguration configuration;
    private DefoldSdkService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        configuration = DefoldSdkServiceConfiguration.builder()
            .location(sdkLocation)
            .sources(List.of(source("public"), source("switch")))
            .cacheSize(3)
            .mappingsCacheSize(3)
            .cacheClearOnExit(true)
            .enableSdkVerification(true)
            .maxVerificationRetryCount(2)
            .build();
        service = new DefoldSdkService(configuration, new SimpleMeterRegistry());
        // Keep these tests independent of a developer's local SDK environment.
        ReflectionTestUtils.setField(service, "dynamoHome", null);
        stubMapping("public", PUBLIC_MAPPING);
        stubMapping("switch", SWITCH_MAPPING);
        stubArchive("public", "public");
        stubArchive("switch", "switch");
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private DefoldSdkServiceConfiguration.Source source(String name) {
        return new DefoldSdkServiceConfiguration.Source(
            server.baseUrl() + "/" + name + "/%s/platform.sdks.json",
            server.baseUrl() + "/" + name + "/%s/defoldsdk.zip");
    }

    private String mappingPath(String source) {
        return "/" + source + "/" + HASH + "/platform.sdks.json";
    }

    private String archivePath(String source) {
        return "/" + source + "/" + HASH + "/defoldsdk.zip";
    }

    private void stubMapping(String source, String body) {
        server.stubFor(get(urlPathMatching("/" + source + "/[^/]+/platform.sdks.json"))
            .willReturn(okJson(body)));
    }

    private byte[] zip(String path, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private void stubArchive(String source, String marker) throws Exception {
        byte[] archive = zip("defoldsdk/source.txt", marker);
        server.stubFor(get(urlEqualTo(archivePath(source))).willReturn(ok().withBody(archive).withFixedDelay(100)));
        String checksum = ExtenderUtil.calculateSHA256(new ByteArrayInputStream(archive));
        server.stubFor(get(urlEqualTo(archivePath(source).replace(".zip", ".sha256"))).willReturn(ok(checksum)));
    }

    private String marker(DefoldSdk sdk) throws Exception {
        return Files.readString(sdk.toFile().toPath().resolve("source.txt"));
    }

    @Test
    void fallsBackToSourceSupportingPlatform() throws Exception {
        ResolvedSdk result = service.resolveSdk(HASH, SWITCH);
        assertEquals("nssdk", result.sdkName());
        assertEquals("2143", result.sdkVersion());
        assertEquals(server.baseUrl() + mappingPath("switch"), result.mappingsUri().toString());
        assertEquals(server.baseUrl() + archivePath("switch"), result.archiveUri().toString());
        server.verify(1, getRequestedFor(urlEqualTo(mappingPath("public"))));
        server.verify(1, getRequestedFor(urlEqualTo(mappingPath("switch"))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cachesResolutionByHashAndPlatform(boolean switchFirst) throws Exception {
        for (String platform : switchFirst ? List.of(SWITCH, LINUX) : List.of(LINUX, SWITCH)) {
            service.resolveSdk(HASH, platform);
        }
        assertEquals("linux", service.resolveSdk(HASH, LINUX).sdkName());
        assertEquals("latest", service.resolveSdk(HASH, LINUX).sdkVersion());
        assertEquals("nssdk", service.resolveSdk(HASH, SWITCH).sdkName());
        assertEquals(2, service.mappingsCache.size());
        server.verify(2, getRequestedFor(urlEqualTo(mappingPath("public"))));
        server.verify(1, getRequestedFor(urlEqualTo(mappingPath("switch"))));
        service.resolveSdk("another-sha", LINUX);
        server.verify(1, getRequestedFor(urlEqualTo("/public/another-sha/platform.sdks.json")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void separatesArchivesForSameHashAndSharesPublicArchive(boolean switchFirst) throws Exception {
        for (String platform : switchFirst ? List.of(SWITCH, LINUX) : List.of(LINUX, SWITCH)) {
            try (DefoldSdk sdk = service.getSdk(HASH, platform)) {
                assertEquals(platform.equals(SWITCH) ? "switch" : "public", marker(sdk));
                assertEquals(HASH, sdk.getHash());
            }
        }
        try (DefoldSdk linux = service.getSdk(HASH, LINUX);
             DefoldSdk android = service.getSdk(HASH, ANDROID);
             DefoldSdk nintendo = service.getSdk(HASH, SWITCH)) {
            assertEquals(linux.toFile(), android.toFile());
            assertNotEquals(linux.toFile(), nintendo.toFile());
        }
        DefoldSdkService restarted = restartService();
        try (DefoldSdk sdk = restarted.getSdk(HASH, SWITCH)) {
            assertEquals("switch", marker(sdk));
        }
        server.verify(1, getRequestedFor(urlEqualTo(archivePath("public"))));
        server.verify(1, getRequestedFor(urlEqualTo(archivePath("switch"))));
    }

    @Test
    void concurrentPlatformsShareOnlyTheirSelectedArchive() throws Exception {
        List<String> platforms = List.of(LINUX, SWITCH, ANDROID, SWITCH, LINUX, ANDROID);
        CountDownLatch start = new CountDownLatch(1);
        List<DefoldSdk> acquired = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(platforms.size())) {
            List<Future<DefoldSdk>> tasks = new ArrayList<>();
            for (String platform : platforms) {
                tasks.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return service.getSdk(HASH, platform);
                }));
            }
            start.countDown();
            for (int i = 0; i < tasks.size(); ++i) {
                DefoldSdk sdk = tasks.get(i).get(10, TimeUnit.SECONDS);
                acquired.add(sdk);
                assertEquals(platforms.get(i).equals(SWITCH) ? "switch" : "public", marker(sdk));
            }
            assertEquals(4, service.getSdkRefCount(service.resolveSdk(HASH, LINUX).cacheKey()));
            assertEquals(2, service.getSdkRefCount(service.resolveSdk(HASH, SWITCH).cacheKey()));
        } finally {
            acquired.forEach(DefoldSdk::close);
        }
        server.verify(3, getRequestedFor(urlEqualTo(mappingPath("public"))));
        server.verify(1, getRequestedFor(urlEqualTo(mappingPath("switch"))));
        server.verify(1, getRequestedFor(urlEqualTo(archivePath("public"))));
        server.verify(1, getRequestedFor(urlEqualTo(archivePath("switch"))));
    }

    @Test
    void evictionAndCopiesUseArchiveReferenceCounts() throws Exception {
        configuration.setCacheSize(0);
        String publicKey = service.resolveSdk(HASH, LINUX).cacheKey();
        String switchKey = service.resolveSdk(HASH, SWITCH).cacheKey();
        try (DefoldSdk linux = service.getSdk(HASH, LINUX);
             DefoldSdk copy = DefoldSdk.copyOf(linux);
             DefoldSdk nintendo = service.getSdk(HASH, SWITCH)) {
            assertEquals(2, service.getSdkRefCount(publicKey));
            assertEquals(1, service.getSdkRefCount(switchKey));
            service.evictCache();
            assertEquals("public", marker(copy));
            assertEquals("switch", marker(nintendo));
        }
        assertEquals(0, service.getSdkRefCount(publicKey));
        assertEquals(0, service.getSdkRefCount(switchKey));
        service.evictCache();
        assertFalse(Files.exists(sdkLocation.resolve(publicKey)));
        assertFalse(Files.exists(sdkLocation.resolve(switchKey)));
    }

    @Test
    void ignoresLegacyHashOnlyCacheDirectory() throws Exception {
        Path legacy = sdkLocation.resolve(HASH).resolve("defoldsdk");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("source.txt"), "wrong legacy SDK");
        try (DefoldSdk sdk = service.getSdk(HASH, SWITCH)) {
            assertEquals("switch", marker(sdk));
            assertNotEquals(legacy, sdk.toFile().toPath());
        }
        server.verify(1, getRequestedFor(urlEqualTo(archivePath("switch"))));
    }

    @Test
    void missingPlatformDoesNotPoisonSuccessfulResolutionOrLaterPublication() throws Exception {
        stubMapping("switch", PUBLIC_MAPPING);
        PlatformNotSupportedException error = assertThrows(PlatformNotSupportedException.class,
            () -> service.resolveSdk(HASH, SWITCH));
        assertTrue(error.getMessage().contains(HASH));
        assertTrue(error.getMessage().contains(SWITCH));
        assertEquals("linux", service.resolveSdk(HASH, LINUX).sdkName());
        stubMapping("switch", SWITCH_MAPPING);
        assertEquals("nssdk", service.resolveSdk(HASH, SWITCH).sdkName());
    }

    @Test
    void missingVersionIsRetried() throws Exception {
        server.stubFor(get(urlPathMatching("/.*/platform.sdks.json")).willReturn(notFound()));
        assertThrows(VersionNotSupportedException.class, () -> service.resolveSdk(HASH, SWITCH));
        stubMapping("switch", SWITCH_MAPPING);
        assertEquals("nssdk", service.resolveSdk(HASH, SWITCH).sdkName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid json", "[]", "{\"arm64-nx64\":null}", "{\"arm64-nx64\":[\"nssdk\"]}", "{\"arm64-nx64\":[\"nssdk\",2143]}", "{\"arm64-nx64\":[\"\",\"2143\"]}"})
    void malformedMappingPreservesDiagnosticsAndCanRecover(String body) throws Exception {
        stubMapping("switch", body);
        ExtenderException error = assertThrows(ExtenderException.class, () -> service.resolveSdk(HASH, SWITCH));
        assertFalse(error instanceof PlatformNotSupportedException);
        assertFalse(error instanceof VersionNotSupportedException);
        assertTrue(error.getMessage().contains("source "));
        stubMapping("switch", SWITCH_MAPPING);
        assertEquals("nssdk", service.resolveSdk(HASH, SWITCH).sdkName());
    }

    @Test
    void continuesAfterMalformedSource() throws Exception {
        stubMapping("public", "invalid json");
        assertEquals("nssdk", service.resolveSdk(HASH, SWITCH).sdkName());
    }

    @Test
    void transientMappingFailureIsNotReportedAsUnsupportedOrCached() throws Exception {
        server.stubFor(get(urlEqualTo(mappingPath("switch"))).willReturn(aResponse().withStatus(503)));
        ExtenderException error = assertThrows(ExtenderException.class, () -> service.resolveSdk(HASH, SWITCH));
        assertFalse(error instanceof PlatformNotSupportedException);
        assertTrue(error.getMessage().contains("503"));
        assertTrue(error.getMessage().contains(HASH));
        server.stubFor(get(urlEqualTo(mappingPath("switch"))).willReturn(okJson(SWITCH_MAPPING)));
        assertEquals("nssdk", service.resolveSdk(HASH, SWITCH).sdkName());
    }

    @Test
    void boundsMappingCacheAndKeepsRecentlyUsedResolution() throws Exception {
        configuration.setMappingsCacheSize(2);
        service.resolveSdk("first", LINUX);
        service.resolveSdk("second", LINUX);
        service.resolveSdk("first", LINUX);
        service.resolveSdk("third", LINUX);
        assertEquals(2, service.mappingsCache.size());
        service.resolveSdk("first", LINUX);
        server.verify(1, getRequestedFor(urlEqualTo("/public/first/platform.sdks.json")));
        service.resolveSdk("second", LINUX);
        server.verify(2, getRequestedFor(urlEqualTo("/public/second/platform.sdks.json")));
    }

    @Test
    void badChecksumDoesNotPublishCacheAndNextRequestCanRetry() throws Exception {
        String checksumPath = archivePath("switch").replace(".zip", ".sha256");
        server.stubFor(get(urlEqualTo(checksumPath)).willReturn(ok("bad checksum")));
        String key = service.resolveSdk(HASH, SWITCH).cacheKey();
        ExtenderException error = assertThrows(ExtenderException.class, () -> service.getSdk(HASH, SWITCH));
        assertTrue(error.getMessage().contains("checksum verification failed"));
        assertEquals(0, service.getSdkRefCount(key));
        assertFalse(Files.exists(sdkLocation.resolve(key)));
        server.verify(2, getRequestedFor(urlEqualTo(archivePath("switch"))));
        stubArchive("switch", "switch");
        try (DefoldSdk sdk = service.getSdk(HASH, SWITCH)) {
            assertEquals("switch", marker(sdk));
            assertTrue(sdk.isValid());
        }
    }

    @Test
    void requiresChecksumWhenVerificationIsEnabled() throws Exception {
        server.stubFor(get(urlEqualTo(archivePath("switch").replace(".zip", ".sha256"))).willReturn(notFound()));
        ExtenderException error = assertThrows(ExtenderException.class, () -> service.getSdk(HASH, SWITCH));
        assertTrue(error.getMessage().contains("checksum returned HTTP 404"));
        assertFalse(Files.exists(sdkLocation.resolve(service.resolveSdk(HASH, SWITCH).cacheKey())));
    }

    @Test
    void selectedArchiveFailureDoesNotFallBackToPublicArchive() {
        server.stubFor(get(urlEqualTo(archivePath("switch"))).willReturn(aResponse().withStatus(503)));
        ExtenderException error = assertThrows(ExtenderException.class, () -> service.getSdk(HASH, SWITCH));
        assertTrue(error.getMessage().contains("SDK archive returned HTTP 503"));
        server.verify(0, getRequestedFor(urlEqualTo(archivePath("public"))));
    }

    @Test
    void validatesArchiveStructureEvenWithoutChecksumVerification() throws Exception {
        configuration.setEnableSdkVerification(false);
        server.stubFor(get(urlEqualTo(archivePath("switch"))).willReturn(ok().withBody(zip("wrong/source.txt", "bad"))));
        ExtenderException error = assertThrows(ExtenderException.class, () -> service.getSdk(HASH, SWITCH));
        assertTrue(error.getMessage().contains("does not contain a defoldsdk directory"));
        assertFalse(Files.exists(sdkLocation.resolve(service.resolveSdk(HASH, SWITCH).cacheKey())));
    }

    @Test
    void localSdkDoesNotUseRemoteSourcesOrResolutionCache() throws Exception {
        Path local = sdkLocation.resolve("local");
        Files.createDirectories(local);
        Files.writeString(local.resolve("platform.sdks.json"), SWITCH_MAPPING);
        ReflectionTestUtils.setField(service, "dynamoHome", local.toFile());
        assertEquals("2143", service.resolveSdk("local", SWITCH).sdkVersion());
        try (DefoldSdk sdk = service.getSdk("local", SWITCH)) {
            assertEquals(local.toFile(), sdk.toFile());
            assertEquals("local", sdk.getHash());
        }
        Files.writeString(local.resolve("platform.sdks.json"), PUBLIC_MAPPING);
        assertThrows(PlatformNotSupportedException.class, () -> service.resolveSdk("local", SWITCH));
        assertEquals(0, service.getSdkRefCount("local"));
        assertTrue(server.getAllServeEvents().isEmpty());
    }

    @Test
    void validatesSourcePairs() {
        configuration.setSources(List.of(new DefoldSdkServiceConfiguration.Source("http://example.com/%s.json", null)));
        assertThrows(IllegalArgumentException.class, () -> new DefoldSdkService(configuration, new SimpleMeterRegistry()));
    }
    private DefoldSdkService restartService() throws Exception {
        DefoldSdkService restarted = new DefoldSdkService(configuration, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(restarted, "dynamoHome", null);
        return restarted;
    }

    @Test
    void selectedSourceSurvivesRecoveryAndDifferentBuilderCacheContents() throws Exception {
        server.stubFor(get(urlEqualTo(mappingPath("public"))).willReturn(aResponse().withStatus(503)));
        ResolvedSdk frontend = service.resolveSdk(HASH, SWITCH);
        assertEquals("2143", frontend.sdkVersion());
        server.stubFor(get(urlEqualTo(mappingPath("public")))
            .willReturn(okJson("{\"arm64-nx64\":[\"nssdk\",\"new\"]}")));
        DefoldSdkService builder = restartService();
        assertEquals("new", builder.resolveSdk(HASH, SWITCH).sdkVersion());
        ResolvedSdk pinned = builder.resolveSdk(HASH, SWITCH, frontend.selection());
        assertEquals(frontend, pinned);
        try (DefoldSdk sdk = builder.getSdk(pinned)) {
            assertEquals("switch", marker(sdk));
        }
        server.verify(0, getRequestedFor(urlEqualTo(archivePath("public"))));
    }

    @Test
    void rejectsUnknownSourceAndMismatchedSdkVersion() throws Exception {
        SdkSelection unknown = new SdkSelection("0".repeat(64), "nssdk", "2143");
        ExtenderException error = assertThrows(ExtenderException.class, () -> service.resolveSdk(HASH, SWITCH, unknown));
        assertTrue(error.getMessage().contains("not configured"));
        assertTrue(server.getAllServeEvents().isEmpty());
        SdkSelection selected = service.resolveSdk(HASH, SWITCH).selection();
        SdkSelection mismatch = new SdkSelection(selected.sourceId(), selected.sdkName(), "wrong");
        assertThrows(ExtenderException.class, () -> service.resolveSdk(HASH, SWITCH, mismatch));
        assertThrows(ExtenderException.class, () -> service.resolveSdk(HASH, SWITCH, mismatch));
        assertEquals("2143", service.resolveSdk(HASH, SWITCH, selected).sdkVersion());
    }

    @Test
    void pinnedSourceDoesNotFallBackWhenUnavailable() throws Exception {
        SdkSelection selected = service.resolveSdk(HASH, SWITCH).selection();
        stubMapping("public", SWITCH_MAPPING);
        server.stubFor(get(urlEqualTo(mappingPath("switch"))).willReturn(aResponse().withStatus(503)));
        DefoldSdkService builder = restartService();
        server.resetRequests();
        assertThrows(ExtenderException.class, () -> builder.resolveSdk(HASH, SWITCH, selected));
        server.verify(0, getRequestedFor(urlEqualTo(mappingPath("public"))));
    }

    @Test
    void cachedArchiveWorksDuringMappingOutageAfterEvictionAndRestart() throws Exception {
        configuration.setMappingsCacheSize(1);
        Path archive;
        SdkSelection selection;
        try (DefoldSdk sdk = service.getSdk(HASH, LINUX)) {
            archive = sdk.toFile().toPath();
            selection = service.resolveSdk(HASH, LINUX).selection();
        }
        service.resolveSdk("another-sha", LINUX); // evict the successful resolution
        server.stubFor(get(urlPathMatching("/.*/platform.sdks.json")).willReturn(aResponse().withStatus(503)));
        server.resetRequests();
        try (DefoldSdk sdk = service.getSdk(HASH, LINUX)) {
            assertEquals(archive, sdk.toFile().toPath());
            assertEquals("public", marker(sdk));
        }
        DefoldSdkService restarted = restartService();
        try (DefoldSdk sdk = restarted.getSdk(HASH, LINUX)) {
            assertEquals(archive, sdk.toFile().toPath());
        }
        assertEquals(selection, restarted.resolveSdk(HASH, LINUX, selection).selection());
        assertTrue(server.getAllServeEvents().isEmpty());
    }

    @Test
    void corruptMetadataFallsBackToRemoteResolution() throws Exception {
        Path directory;
        try (DefoldSdk sdk = service.getSdk(HASH, SWITCH)) {
            directory = sdk.toFile().toPath().getParent();
        }
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.getFileName().toString().startsWith("resolution-")).toList()) {
                Files.writeString(path, "{broken");
            }
        }
        server.resetRequests();
        try (DefoldSdk sdk = restartService().getSdk(HASH, SWITCH)) {
            assertEquals("switch", marker(sdk));
        }
        server.verify(1, getRequestedFor(urlEqualTo(mappingPath("switch"))));
        server.verify(0, getRequestedFor(urlEqualTo(archivePath("switch"))));
    }

    @Test
    void removedSourcesCannotBeResurrectedFromArchiveMetadata() throws Exception {
        try (DefoldSdk sdk = service.getSdk(HASH, SWITCH)) {
            assertEquals("switch", marker(sdk));
        }
        configuration.setSources(List.of(source("public")));
        assertThrows(PlatformNotSupportedException.class, () -> restartService().getSdk(HASH, SWITCH));
    }

    @Test
    void errorsAndLogsDoNotExposeUrlCredentialsOrParserContents() throws Exception {
        String secret = "secret-review-token";
        String base = server.baseUrl().replace("http://", "http://credential-user:credential-password@");
        configuration.setSources(List.of(new DefoldSdkServiceConfiguration.Source(
            base + "/switch/%s/platform.sdks.json?token=" + secret,
            base + "/switch/%s/defoldsdk.zip?token=" + secret)));
        DefoldSdkService secured = restartService();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DefoldSdkService.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            server.stubFor(get(urlPathEqualTo(mappingPath("switch"))).willReturn(aResponse().withStatus(503)));
            ExtenderException error = assertThrows(ExtenderException.class, () -> secured.resolveSdk(HASH, SWITCH));
            assertTrue(error.getMessage().contains("503"));
            assertFalse(error.getMessage().contains(secret));
            assertFalse(error.getMessage().contains("credential-"));
            server.stubFor(get(urlPathEqualTo(mappingPath("switch"))).willReturn(okJson("{\"" + secret + "\":invalid}")));
            error = assertThrows(ExtenderException.class, () -> secured.resolveSdk(HASH, SWITCH));
            assertFalse(error.getMessage().contains(secret));
            server.stubFor(get(urlPathEqualTo(mappingPath("switch"))).willReturn(okJson(SWITCH_MAPPING)));
            ResolvedSdk resolved = secured.resolveSdk(HASH, SWITCH);
            assertFalse(resolved.toString().contains(secret));
            server.stubFor(get(urlPathEqualTo(archivePath("switch"))).willReturn(aResponse().withStatus(503)));
            error = assertThrows(ExtenderException.class, () -> secured.getSdk(resolved));
            assertTrue(error.getMessage().contains("503"));
            assertFalse(error.getMessage().contains(secret));
            assertFalse(error.getMessage().contains("credential-"));
            synchronized (logs) {
                for (var event : logs.list) {
                    assertFalse(event.getFormattedMessage().contains(secret));
                    assertFalse(event.getFormattedMessage().contains("credential-"));
                }
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void persistedMetadataDoesNotContainConfiguredUrls() throws Exception {
        configuration.setEnableSdkVerification(false);
        configuration.setSources(List.of(new DefoldSdkServiceConfiguration.Source(
            server.baseUrl() + "/switch/%s/platform.sdks.json?token=secret-token",
            server.baseUrl() + "/switch/%s/defoldsdk.zip?token=secret-token")));
        server.stubFor(get(urlPathEqualTo(mappingPath("switch"))).willReturn(okJson(SWITCH_MAPPING)));
        server.stubFor(get(urlPathEqualTo(archivePath("switch"))).willReturn(ok().withBody(zip("defoldsdk/source.txt", "switch"))));
        try (DefoldSdk sdk = restartService().getSdk(HASH, SWITCH);
             var files = Files.list(sdk.toFile().toPath().getParent())) {
            List<Path> metadata = files.filter(p -> p.getFileName().toString().startsWith("resolution-")).toList();
            assertEquals(1, metadata.size());
            String text = Files.readString(metadata.get(0));
            assertFalse(text.contains("secret-token"));
            assertFalse(text.contains("http"));
            assertTrue(text.contains(HASH));
        }
    }

}
