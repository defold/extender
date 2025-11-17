package com.defold.extender.services;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.defold.extender.remote.RemoteInstanceConfig;
import com.defold.extender.services.HealthReporterService.OperationalStatus;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;

public class HealthReporterServiceTest {
    private static final String OPERATIONAL_RESPONSE = JSONObject.toJSONString(Collections.singletonMap("status", OperationalStatus.Operational.toString()));
    private static final String UNREACHABLE_RESPONSE = JSONObject.toJSONString(Collections.singletonMap("status", OperationalStatus.Unreachable.toString()));

    private static Path tmpHTTPRoot = Path.of("/tmp/__health_check_http");

    private static WireMockServer normalServer1;
    private static WireMockServer normalServer2;
    private static WireMockServer unreachableServer;
    private static WireMockServer timeoutServer;

    private static HealthReporterService service;

    @BeforeAll
    public static void beforeAll() throws IOException {
        Files.createDirectories(tmpHTTPRoot);

        normalServer1 = new WireMockServer(WireMockConfiguration.options()
            .port(9678)
            .withRootDirectory(tmpHTTPRoot.toString())
        );
        normalServer1.start();

        normalServer1.stubFor(get(urlEqualTo("/health_report"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(OPERATIONAL_RESPONSE)
                        .withHeader("Content-Type", "application/json")));
        
        normalServer2 = new WireMockServer(WireMockConfiguration.options()
            .port(9679)
            .withRootDirectory(tmpHTTPRoot.toString())
        );
        normalServer2.start();

        normalServer2.stubFor(get(urlEqualTo("/health_report"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(OPERATIONAL_RESPONSE)
                        .withHeader("Content-Type", "application/json")));

        unreachableServer = new WireMockServer(WireMockConfiguration.options()
            .port(9680)
            .withRootDirectory(tmpHTTPRoot.toString())
        );
        unreachableServer.start();

        unreachableServer.stubFor(get(urlEqualTo("/health_report"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(UNREACHABLE_RESPONSE)
                        .withHeader("Content-Type", "application/json")));

        timeoutServer = new WireMockServer(WireMockConfiguration.options()
            .port(9681)
            .withRootDirectory(tmpHTTPRoot.toString())
        );
        timeoutServer.start();

        timeoutServer.stubFor(get(urlEqualTo("/health_report"))
                .willReturn(aResponse()
                        .withFixedDelay(15000)
                        .withStatus(200)
                        .withBody(OPERATIONAL_RESPONSE)
                        .withHeader("Content-Type", "application/json")));

        service = new HealthReporterService(Optional.empty(), new SimpleTracer(), Propagator.NOOP);
        service.connectionTimeout = 5000;
    }

    @AfterAll
    public static void afterAll() throws IOException {
        if (normalServer1 != null) {
            normalServer1.stop();
        }
        if (normalServer2 != null) {
            normalServer2.stop();
        }
        if (unreachableServer != null) {
            unreachableServer.stop();
        }
        if (timeoutServer != null) {
            timeoutServer.stop();
        }

        FileUtils.deleteDirectory(tmpHTTPRoot.toFile());
    }

    @Test
    public void testSingleNode() {
        String result = service.collectHealthReport(false, Map.of());
        assertEquals(result, OPERATIONAL_RESPONSE);
    }

    @Test
    public void testRemoteNodesNormal() throws ParseException {
        Map<String, RemoteInstanceConfig> conf = Map.of(
            "linux-latest", new RemoteInstanceConfig("http://localhost:9678", "linux-latest", true),
            "emsdk-406", new RemoteInstanceConfig("http://localhost:9679", "emsdk-406", true)
        );
        JSONObject expected = new JSONObject(Map.of(
            "linux", "Operational",
            "emsdk", "Operational"
        ));
        String response = service.collectHealthReport(true, conf);
        JSONParser jsonParser = new JSONParser();
        JSONObject result = (JSONObject)jsonParser.parse(response);
        assertEquals(expected, result);
    }

    @Test
    public void testRemoteNodesUnreachable() {
        Map<String, RemoteInstanceConfig> conf = Map.of(
            "android-latest", new RemoteInstanceConfig("http://localhost:9680", "android-latest", true)
        );
        Map<String, String> expected = Map.of(
            "android", "Unreachable"
        );
        String result = service.collectHealthReport(true, conf);
        assertEquals(JSONObject.toJSONString(expected), result);
    }

    @Test
    public void testRemoteNodesTimeout() {
        Map<String, RemoteInstanceConfig> conf = Map.of(
            "windows-latest", new RemoteInstanceConfig("http://localhost:9681", "windows-latest", true)
        );
        Map<String, Object> expected = Map.of(
            "windows", "Unreachable"
        );
        String result = service.collectHealthReport(true, conf);
        assertEquals(JSONObject.toJSONString(expected), result);
    }

    @Test
    public void testRemoteNodesNotFullyOperational() {
        Map<String, RemoteInstanceConfig> conf = Map.of(
            "android-ndk25", new RemoteInstanceConfig("http://localhost:9679", "android-ndk25", true),
            "android-latest", new RemoteInstanceConfig("http://localhost:9681", "android-latest", true)
        );
        Map<String, Object> expected = Map.of(
            "android", "NotFullyOperational"
        );
        String result = service.collectHealthReport(true, conf);
        assertEquals(JSONObject.toJSONString(expected), result);
    }
}
