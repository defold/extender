package com.defold.extender.services;

import com.defold.extender.AsyncBuilder;
import com.defold.extender.ExtenderController;
import com.defold.extender.progress.BuildProgressService;
import com.defold.extender.remote.RemoteEngineBuilder;
import com.defold.extender.remote.RemoteHostConfiguration;
import com.defold.extender.remote.RemoteInstanceConfig;
import com.defold.extender.services.data.ResolvedSdk;
import com.defold.extender.services.data.SdkSelection;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SdkDispatchTest {
    @TempDir Path root;
    private WireMockServer source;
    private final List<File> jobs = new ArrayList<>();
    private static final String PLATFORM = "arm64-nx64";
    private static final String HASH = "test-engine-sha";

    @BeforeEach void setUp() {
        source = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        source.start();
        source.stubFor(get(urlPathEqualTo("/public/platform.sdks.json")).willReturn(aResponse().withStatus(503)));
        source.stubFor(get(urlPathEqualTo("/switch/platform.sdks.json"))
            .willReturn(okJson("{\"arm64-nx64\":[\"nssdk\",\"2143\"]}")));
    }

    @AfterEach void tearDown() {
        jobs.forEach(FileUtils::deleteQuietly);
        source.stop();
    }

    private DefoldSdkService service(String name) throws Exception {
        var configuration = DefoldSdkServiceConfiguration.builder().location(root.resolve(name))
            .sources(List.of(
                new DefoldSdkServiceConfiguration.Source(source.baseUrl() + "/public/platform.sdks.json", source.baseUrl() + "/public/%s/defoldsdk.zip"),
                new DefoldSdkServiceConfiguration.Source(source.baseUrl() + "/switch/platform.sdks.json", source.baseUrl() + "/switch/%s/defoldsdk.zip")))
            .cacheSize(3).mappingsCacheSize(5).build();
        var service = new DefoldSdkService(configuration, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "dynamoHome", null);
        return service;
    }

    private MockMvc controller(DefoldSdkService sdkService, ExtenderController.InstanceType type,
                               AsyncBuilder local, RemoteEngineBuilder remote) throws Exception {
        DataCacheService cache = mock(DataCacheService.class);
        when(cache.getCachedFiles(any())).thenReturn(new DataCacheService.DataCacheServiceInfo());
        when(cache.cacheFiles(any())).thenReturn(new DataCacheService.DataCacheServiceInfo());
        var hosts = new RemoteHostConfiguration();
        hosts.getPlatforms().put("nssdk-2143", new RemoteInstanceConfig("http://builder", "nssdk-2143", true));
        var controller = new ExtenderController(sdkService, cache, mock(UserUpdateService.class),
            new SimpleMeterRegistry(), local, remote, hosts, mock(HealthReporterService.class),
            new BuildProgressService(false, 30000, 256, 8, 1200000), true, "1024mb", root.resolve("results").toString());
        ReflectionTestUtils.setField(controller, "instanceType", type);
        doAnswer(call -> { jobs.add(call.getArgument(4)); return null; }).when(local)
            .asyncBuildEngine(any(), anyString(), anyString(), any(), any(), any(), any());
        doAnswer(call -> { jobs.add(call.getArgument(5)); return null; }).when(remote)
            .buildAsync(any(), any(), anyString(), anyString(), any(), any(), any());
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private MockMultipartHttpServletRequestBuilder request(SdkSelection selection) {
        var request = multipart("/build_async/" + PLATFORM + "/" + HASH)
            .file("app.manifest", "name: test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (selection != null) {
            request.header(SdkSelection.SOURCE_HEADER, selection.sourceId());
            request.header(SdkSelection.NAME_HEADER, selection.sdkName());
            request.header(SdkSelection.VERSION_HEADER, selection.sdkVersion());
        }
        return request;
    }

    @Test void frontendSelectionReachesBuilderAndAsyncBuildUnchanged() throws Exception {
        var remote = mock(RemoteEngineBuilder.class);
        var frontend = controller(service("frontend"), ExtenderController.InstanceType.FRONTEND_ONLY,
            mock(AsyncBuilder.class), remote);
        frontend.perform(request(null)).andExpect(status().isOk());
        var selected = ArgumentCaptor.forClass(SdkSelection.class);
        verify(remote).buildAsync(any(), any(), eq(PLATFORM), eq(HASH), selected.capture(), any(), any());
        assertEquals("2143", selected.getValue().sdkVersion());
        source.stubFor(get(urlPathEqualTo("/public/platform.sdks.json"))
            .willReturn(okJson("{\"arm64-nx64\":[\"nssdk\",\"new\"]}")));
        var builderService = service("builder");
        assertEquals("new", builderService.resolveSdk(HASH, PLATFORM).sdkVersion());
        var local = mock(AsyncBuilder.class);
        var builder = controller(builderService, ExtenderController.InstanceType.BUILDER_ONLY, local,
            mock(RemoteEngineBuilder.class));
        builder.perform(request(selected.getValue())).andExpect(status().isOk())
            .andExpect(header().string(SdkSelection.SOURCE_HEADER, selected.getValue().sourceId()))
            .andExpect(header().string(SdkSelection.VERSION_HEADER, "2143"));
        var resolved = ArgumentCaptor.forClass(ResolvedSdk.class);
        verify(local).asyncBuildEngine(any(), eq(PLATFORM), eq(HASH), resolved.capture(), any(), any(), any());
        assertEquals(selected.getValue(), resolved.getValue().selection());
    }

    @Test void incompleteSelectionIsRejectedBeforeDispatch() throws Exception {
        var local = mock(AsyncBuilder.class);
        var builder = controller(service("builder"), ExtenderController.InstanceType.BUILDER_ONLY, local,
            mock(RemoteEngineBuilder.class));
        builder.perform(request(null).header(SdkSelection.SOURCE_HEADER, "a".repeat(64)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().string("Invalid or incomplete SDK source selection"));
        verifyNoInteractions(local);
        assertTrue(source.getAllServeEvents().isEmpty());
    }

    @Test void frontendDoesNotAcceptCallerSelectedSources() throws Exception {
        var remote = mock(RemoteEngineBuilder.class);
        var frontend = controller(service("frontend"), ExtenderController.InstanceType.FRONTEND_ONLY,
            mock(AsyncBuilder.class), remote);
        frontend.perform(request(new SdkSelection("a".repeat(64), "nssdk", "2143")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().string("SDK source selection is only accepted by builder instances"));
        verifyNoInteractions(remote);
    }

    @Test void unknownSourceIsRejectedBeforeAsyncBuild() throws Exception {
        var local = mock(AsyncBuilder.class);
        var builder = controller(service("builder"), ExtenderController.InstanceType.BUILDER_ONLY, local,
            mock(RemoteEngineBuilder.class));
        builder.perform(request(new SdkSelection("a".repeat(64), "nssdk", "2143")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().string("SDK source selected by the frontend is not configured on this builder"));
        verifyNoInteractions(local);
        assertTrue(source.getAllServeEvents().isEmpty());
    }
}
