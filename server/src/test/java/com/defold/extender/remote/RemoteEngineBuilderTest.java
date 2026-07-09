package com.defold.extender.remote;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.defold.extender.BuilderConstants;
import com.defold.extender.metrics.MetricsWriter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;

public class RemoteEngineBuilderTest {

    private static final long BUILD_SLEEP_TIMEOUT = 100;
    private static final long BUILD_RESULT_WAIT_TIMEOUT = 10_000;
    private static final int SOCKET_TIMEOUT = 2_000;

    @TempDir
    Path tmpDir;

    private WireMockServer builderMock;
    private ExecutorService executor;
    private Path resultLocation;

    @BeforeEach
    public void setUp() throws Exception {
        builderMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        builderMock.start();
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        resultLocation = Files.createDirectories(tmpDir.resolve("results"));
    }

    @AfterEach
    public void tearDown() {
        executor.shutdownNow();
        builderMock.stop();
    }

    private RemoteEngineBuilder createBuilder(int resultDownloadRetries) {
        return new RemoteEngineBuilder(
            Optional.empty(),
            resultLocation.toString(),
            BUILD_SLEEP_TIMEOUT,
            BUILD_RESULT_WAIT_TIMEOUT,
            SOCKET_TIMEOUT,
            SOCKET_TIMEOUT,
            SOCKET_TIMEOUT,
            35,
            resultDownloadRetries,
            100,
            new SimpleTracer(),
            Propagator.NOOP);
    }

    private RemoteInstanceConfig mockInstanceConfig() {
        return new RemoteInstanceConfig(String.format("http://localhost:%d", builderMock.port()), "osx-latest", true);
    }

    private Path submitBuild(RemoteEngineBuilder builder, String jobName) throws Exception {
        Path projectDirectory = Files.createDirectories(tmpDir.resolve(jobName).resolve("upload"));
        Files.writeString(projectDirectory.resolve("main.cpp"), "int main() { return 0; }");
        File jobDirectory = Files.createDirectories(tmpDir.resolve(jobName).resolve("job_" + jobName)).toFile();
        executor.submit(() -> {
            builder.buildAsync(mockInstanceConfig(), projectDirectory.toFile(), "arm64-ios", "testsdk",
                jobDirectory, new MetricsWriter(new SimpleMeterRegistry()));
            return null;
        });
        return resultLocation.resolve(jobDirectory.getName());
    }

    private int receivedBuildRequests() {
        return builderMock.findAll(postRequestedFor(urlPathMatching("/build_async/.*"))).size();
    }

    private boolean waitFor(Supplier<Boolean> condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.get()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(100);
        }
        return true;
    }

    private void stubSuccessfulJobPipeline(String jobId) {
        builderMock.stubFor(post(urlPathMatching("/build_async/.*"))
                .willReturn(aResponse().withStatus(200).withBody(jobId)));
        builderMock.stubFor(get(urlPathEqualTo("/job_status"))
                .willReturn(aResponse().withStatus(200)
                        .withBody(String.valueOf(BuilderConstants.JobStatus.SUCCESS.ordinal()))));
    }

    // Repro for https://github.com/defold/extender/issues/957: builds whose job_result
    // download stalls must not block other builds to the same builder.
    @Test
    @Timeout(60)
    public void stalledResultDownloadMustNotBlockOtherBuilds() throws Exception {
        stubSuccessfulJobPipeline("stalled-job");
        builderMock.stubFor(get(urlPathEqualTo("/job_result"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(30_000).withBody(new byte[] {0x42})));

        RemoteEngineBuilder builder = createBuilder(1);

        // Occupy the connection pool with builds stuck downloading their result
        Path stalledResult1 = submitBuild(builder, "stalled1");
        Path stalledResult2 = submitBuild(builder, "stalled2");
        assertTrue(waitFor(() -> receivedBuildRequests() == 2, 5_000));
        // Both builds are polling towards the stalled /job_result; give them time to get stuck there
        Thread.sleep(3 * BUILD_SLEEP_TIMEOUT + 1_000);

        submitBuild(builder, "probe");
        boolean probeDispatched = waitFor(() -> receivedBuildRequests() == 3, 5_000);
        assertTrue(probeDispatched,
            "Frozen router: the probe build's POST /build_async never reached the remote builder "
            + "while other builds were stuck downloading /job_result (defold/extender#957)");

        // The stalled builds must fail gracefully instead of hanging forever
        assertTrue(waitFor(() -> stalledResult1.resolve(BuilderConstants.BUILD_ERROR_FILENAME).toFile().exists()
                && stalledResult2.resolve(BuilderConstants.BUILD_ERROR_FILENAME).toFile().exists(), 15_000),
            "Stalled builds did not get marked with an error result");
    }

    // A result download interrupted mid-body is retried while the result is still on the builder
    @Test
    @Timeout(60)
    public void interruptedResultDownloadIsRetried() throws Exception {
        stubSuccessfulJobPipeline("flaky-job");
        final byte[] resultZip = new byte[] {0x50, 0x4b, 0x03, 0x04, 0x42};
        builderMock.stubFor(get(urlPathEqualTo("/job_result")).inScenario("flaky download")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK))
                .willSetStateTo("recovered"));
        builderMock.stubFor(get(urlPathEqualTo("/job_result")).inScenario("flaky download")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody(resultZip)));

        Path resultDir = submitBuild(createBuilder(3), "flaky");

        Path buildResult = resultDir.resolve(BuilderConstants.BUILD_RESULT_FILENAME);
        assertTrue(waitFor(() -> buildResult.toFile().exists(), 15_000),
            "Build result was not downloaded although the builder had it ready for a retry");
        assertArrayEquals(resultZip, Files.readAllBytes(buildResult));
        assertEquals(2, builderMock.findAll(getRequestedFor(urlPathEqualTo("/job_result"))).size());
    }

    @Test
    @Timeout(60)
    public void rejectedBuildIsMarkedAsError() throws Exception {
        builderMock.stubFor(post(urlPathMatching("/build_async/.*"))
                .willReturn(aResponse().withStatus(500).withBody("no space left on device")));

        Path resultDir = submitBuild(createBuilder(1), "rejected");

        Path errorResult = resultDir.resolve(BuilderConstants.BUILD_ERROR_FILENAME);
        assertTrue(waitFor(() -> errorResult.toFile().exists(), 15_000));
        assertEquals("no space left on device", Files.readString(errorResult));
    }
}
