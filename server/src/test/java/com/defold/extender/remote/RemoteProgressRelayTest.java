package com.defold.extender.remote;

import com.defold.extender.progress.BuildProgressService;
import com.defold.extender.progress.BuildStage;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class RemoteProgressRelayTest {

    private WireMockServer wireMock;
    private BuildProgressService progressService;

    @BeforeEach
    public void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        progressService = Mockito.mock(BuildProgressService.class);
    }

    @AfterEach
    public void tearDown() {
        wireMock.stop();
    }

    private static String sseEvent(long seq, String json) {
        return String.format("id:%d\ndata:%s\n\n", seq, json);
    }

    @Test
    public void testRelaysEventsWithRewrittenJobIdAndSuppressesTerminal() {
        String body = ":ka\n\n"
                + sseEvent(1, "{\"jobId\":\"remoteJob\",\"seq\":1,\"stage\":\"SDK\",\"detail\":\"Downloading\",\"percent\":1,\"terminal\":false}")
                + sseEvent(2, "{\"jobId\":\"remoteJob\",\"seq\":2,\"stage\":\"COMPILING\",\"detail\":\"ext1\",\"percent\":40,\"extension\":\"ext1\",\"currentFile\":2,\"totalFiles\":10,\"terminal\":false}")
                + sseEvent(3, "{\"jobId\":\"remoteJob\",\"seq\":3,\"stage\":\"SUCCESS\",\"detail\":\"done\",\"percent\":100,\"terminal\":true}");
        wireMock.stubFor(get(urlPathEqualTo("/job_progress"))
                .withQueryParam("jobId", equalTo("remoteJob"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(body)));

        RemoteProgressRelay relay = new RemoteProgressRelay(wireMock.baseUrl(), "remoteJob", "localJob", progressService);
        relay.run();

        verify(progressService).publishRaw(eq("localJob"), eq(BuildStage.SDK), eq("Downloading"), eq(1),
                eq(null), eq(null), eq(null));
        verify(progressService).publishRaw(eq("localJob"), eq(BuildStage.COMPILING), eq("ext1"), eq(40),
                eq("ext1"), eq(2), eq(10));
        // the remote terminal event is suppressed: the frontend emits its own
        verify(progressService, never()).publishRaw(anyString(), eq(BuildStage.SUCCESS), anyString(), anyInt(), any(), any(), any());
        verify(progressService, never()).publishRaw(anyString(), eq(BuildStage.ERROR), anyString(), anyInt(), any(), any(), any());
    }

    @Test
    public void testOldRemoteBuilderWithout404GivesUpSilently() {
        wireMock.stubFor(get(urlPathEqualTo("/job_progress"))
                .willReturn(aResponse().withStatus(404)));

        RemoteProgressRelay relay = new RemoteProgressRelay(wireMock.baseUrl(), "remoteJob", "localJob", progressService);
        long start = System.currentTimeMillis();
        relay.run();

        // gives up immediately (no reconnect loop) and relays nothing
        assertFalse(System.currentTimeMillis() - start > 5000, "relay must not retry on 404");
        verify(progressService, never()).publishRaw(anyString(), any(), anyString(), anyInt(), any(), any(), any());
        assertFalse(relay.isDeliveringEvents());
    }
}
