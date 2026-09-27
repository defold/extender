package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class DefoldSdkMappingsTest {
    private static final String LINUX = "x86_64-linux";
    private static final String SWITCH = "arm64-nx64";
    private static final String PUBLIC_MAPPING = "{\"x86_64-linux\":[\"linux\",\"latest\"]}";
    private static final String SWITCH_MAPPING = "{\"arm64-nx64\":[\"nssdk\",\"2143\"]}";

    @TempDir Path sdkLocation;
    private WireMockServer server;
    private DefoldSdkService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        var configuration = DefoldSdkServiceConfiguration.builder()
            .location(sdkLocation)
            .mappingsUrls(new String[]{server.baseUrl() + "/public/%s.json", server.baseUrl() + "/switch/%s.json"})
            .build();
        service = new DefoldSdkService(configuration, new SimpleMeterRegistry());
        server.stubFor(get(urlPathMatching("/public/.*\\.json")).willReturn(okJson(PUBLIC_MAPPING)));
        server.stubFor(get(urlPathMatching("/switch/.*\\.json")).willReturn(okJson(SWITCH_MAPPING)));
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fallsBackAndCachesByEngineAndPlatform(boolean switchFirst) throws Exception {
        for (String platform : switchFirst ? List.of(SWITCH, LINUX) : List.of(LINUX, SWITCH)) {
            service.getPlatformSdkMappings("engine", platform);
        }
        assertArrayEquals(new String[]{"linux", "latest"}, ExtenderUtil.getSdksForPlatform(LINUX,
            service.getPlatformSdkMappings("engine", LINUX)));
        assertArrayEquals(new String[]{"nssdk", "2143"}, ExtenderUtil.getSdksForPlatform(SWITCH,
            service.getPlatformSdkMappings("engine", SWITCH)));
        server.verify(2, getRequestedFor(urlEqualTo("/public/engine.json")));
        server.verify(1, getRequestedFor(urlEqualTo("/switch/engine.json")));
        service.getPlatformSdkMappings("another-engine", LINUX);
        server.verify(1, getRequestedFor(urlEqualTo("/public/another-engine.json")));
    }

    @Test
    void unsupportedPlatformIsNotCached() throws Exception {
        server.stubFor(get(urlEqualTo("/switch/engine.json")).willReturn(okJson("{}")));
        JSONObject mappings = service.getPlatformSdkMappings("engine", SWITCH);
        assertThrows(NullPointerException.class, () -> ExtenderUtil.getSdksForPlatform(SWITCH, mappings));
        assertTrue(service.mappingsCache.isEmpty());
        server.stubFor(get(urlEqualTo("/switch/engine.json")).willReturn(okJson(SWITCH_MAPPING)));
        assertTrue(service.getPlatformSdkMappings("engine", SWITCH).containsKey(SWITCH));
        server.verify(2, getRequestedFor(urlEqualTo("/switch/engine.json")));
    }

    @Test
    void unavailableManifestsCanBeRetried() throws Exception {
        server.stubFor(get(urlEqualTo("/public/engine.json")).willReturn(notFound()));
        server.stubFor(get(urlEqualTo("/switch/engine.json")).willReturn(notFound()));
        assertThrows(ExtenderException.class, () -> service.getPlatformSdkMappings("engine", SWITCH));
        assertTrue(service.mappingsCache.isEmpty());
        server.stubFor(get(urlEqualTo("/switch/engine.json")).willReturn(okJson(SWITCH_MAPPING)));
        assertTrue(service.getPlatformSdkMappings("engine", SWITCH).containsKey(SWITCH));
    }

    @Test
    void concurrentRequestsForDifferentPlatformsDoNotShareAResolution() throws Exception {
        server.stubFor(get(urlEqualTo("/public/engine.json"))
            .willReturn(okJson(PUBLIC_MAPPING).withFixedDelay(500)));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var linux = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return service.getPlatformSdkMappings("engine", LINUX);
            });
            var nintendo = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return service.getPlatformSdkMappings("engine", SWITCH);
            });
            start.countDown();
            assertTrue(linux.get(10, TimeUnit.SECONDS).containsKey(LINUX));
            assertTrue(nintendo.get(10, TimeUnit.SECONDS).containsKey(SWITCH));
        }
        server.verify(2, getRequestedFor(urlEqualTo("/public/engine.json")));
        server.verify(1, getRequestedFor(urlEqualTo("/switch/engine.json")));
    }
}
