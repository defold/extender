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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.apache.http.HttpEntity;

import com.defold.extender.BuilderConstants;
import com.defold.extender.ExtenderConst;
import com.defold.extender.ExtenderException;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.services.DataCacheService;
import com.defold.extender.services.GCPInstanceService;
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
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    public void setUp() throws Exception {
        builderMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        builderMock.start();
        meterRegistry = new SimpleMeterRegistry();
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
        return createBuilder(resultDownloadRetries, BUILD_RESULT_WAIT_TIMEOUT);
    }

    private RemoteEngineBuilder createBuilder(int resultDownloadRetries, long buildResultWaitTimeout) {
        return createBuilder(Optional.empty(), resultDownloadRetries, buildResultWaitTimeout);
    }

    private RemoteEngineBuilder createBuilder(Optional<GCPInstanceService> instanceService,
                                              int resultDownloadRetries, long buildResultWaitTimeout) {
        return new RemoteEngineBuilder(
            instanceService,
            resultLocation.toString(),
            BUILD_SLEEP_TIMEOUT,
            buildResultWaitTimeout,
            SOCKET_TIMEOUT,
            SOCKET_TIMEOUT,
            SOCKET_TIMEOUT,
            35,
            resultDownloadRetries,
            100,
            meterRegistry,
            new SimpleTracer(),
            Propagator.NOOP);
    }

    private double reconnectCount(String operation) {
        return meterRegistry.counter("extender.service.remoteBuilder.reconnect",
            "host", "localhost:" + builderMock.port(), "operation", operation).count();
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
        assertEquals(1.0, reconnectCount("job_result"),
            "The retried result download must be counted as a reconnect to the builder");
    }

    // A connection dropped before the response arrives (e.g. closed by a NAT or the builder
    // restarting) is retried by the HTTP client on a fresh connection and shows up in metrics
    @Test
    @Timeout(60)
    public void droppedStatusConnectionIsRetriedAndCounted() throws Exception {
        builderMock.stubFor(post(urlPathMatching("/build_async/.*"))
                .willReturn(aResponse().withStatus(200).withBody("reconnect-job")));
        builderMock.stubFor(get(urlPathEqualTo("/job_status")).inScenario("dropped connection")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("recovered"));
        builderMock.stubFor(get(urlPathEqualTo("/job_status")).inScenario("dropped connection")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withBody(String.valueOf(BuilderConstants.JobStatus.SUCCESS.ordinal()))));
        final byte[] resultZip = new byte[] {0x50, 0x4b, 0x03, 0x04, 0x42};
        builderMock.stubFor(get(urlPathEqualTo("/job_result"))
                .willReturn(aResponse().withStatus(200).withBody(resultZip)));

        Path resultDir = submitBuild(createBuilder(1), "reconnect");

        Path buildResult = resultDir.resolve(BuilderConstants.BUILD_RESULT_FILENAME);
        assertTrue(waitFor(() -> buildResult.toFile().exists(), 15_000),
            "Build did not recover from a dropped status poll connection");
        assertEquals(2, builderMock.findAll(getRequestedFor(urlPathEqualTo("/job_status"))).size());
        assertEquals(1.0, reconnectCount("job_status"),
            "The client-level retry of the status poll must be counted as a reconnect");
        assertEquals(0.0, reconnectCount("job_result"));
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

    // ---------------------------------------------------------------------------------------------
    // Regression coverage for the fixes #974 did not include (defold/extender remote-builder tests).
    // ---------------------------------------------------------------------------------------------

    // A finished job whose /job_result returns an HTTP error must be reported as an error, never
    // served as a build.zip. Before the fix the 5xx body was copied straight into build.zip and the
    // client downloaded a "successful" archive that was not a zip.
    @Test
    @Timeout(60)
    public void jobResultHttpErrorIsMarkedAsErrorNotACorruptBuildZip() throws Exception {
        stubSuccessfulJobPipeline("result-5xx-job");
        builderMock.stubFor(get(urlPathEqualTo("/job_result"))
                .willReturn(aResponse().withStatus(500).withBody("<html>internal server error</html>")));

        Path resultDir = submitBuild(createBuilder(1), "result5xx");

        assertTrue(errorFileAppears(resultDir), "Expected error.txt for a job_result HTTP 500");
        String error = readError(resultDir);
        assertTrue(error.contains("500") && error.contains("job_result"),
            "error.txt should name the HTTP status and the failing request, was: " + error);
        assertFalse(resultDir.resolve(BuilderConstants.BUILD_RESULT_FILENAME).toFile().exists(),
            "A non-200 job_result must never be written as build.zip");
        assertFalse(resultDir.resolve(BuilderConstants.BUILD_RESULT_FILENAME + ".tmp").toFile().exists(),
            "The temporary result file must not be left behind");
        // A definitive HTTP error is not a transient network fault, so it is not retried.
        assertEquals(1, builderMock.findAll(getRequestedFor(urlPathEqualTo("/job_result"))).size());
    }

    // A non-200 from /job_status must be reported truthfully, and the result must not be fetched.
    // Before the fix, Integer.valueOf(body) threw NumberFormatException and the catch-all wrote the
    // misleading "Failed to communicate with Extender service.".
    @Test
    @Timeout(60)
    public void jobStatusHttpErrorIsMarkedAsErrorAndSkipsResult() throws Exception {
        builderMock.stubFor(post(urlPathMatching("/build_async/.*"))
                .willReturn(aResponse().withStatus(200).withBody("status-5xx-job")));
        builderMock.stubFor(get(urlPathEqualTo("/job_status"))
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        Path resultDir = submitBuild(createBuilder(1), "status5xx");

        assertTrue(errorFileAppears(resultDir));
        String error = readError(resultDir);
        assertTrue(error.contains("503") && error.contains("job_status"),
            "error.txt should report the job_status HTTP failure truthfully, was: " + error);
        assertFalse(error.contains("Failed to communicate"),
            "An HTTP error from the builder is not a communication failure");
        assertFalse(resultDir.resolve(BuilderConstants.BUILD_RESULT_FILENAME).toFile().exists());
        assertTrue(builderMock.findAll(getRequestedFor(urlPathEqualTo("/job_result"))).isEmpty(),
            "The result must not be fetched when the status could not be read");
    }

    // A 200 /job_status with a non-numeric body is a builder fault, reported as such rather than as
    // a communication failure.
    @Test
    @Timeout(60)
    public void malformedJobStatusBodyIsReportedNotTreatedAsCommunicationFailure() throws Exception {
        builderMock.stubFor(post(urlPathMatching("/build_async/.*"))
                .willReturn(aResponse().withStatus(200).withBody("malformed-job")));
        builderMock.stubFor(get(urlPathEqualTo("/job_status"))
                .willReturn(aResponse().withStatus(200).withBody("not-a-number")));

        Path resultDir = submitBuild(createBuilder(1), "malformed");

        assertTrue(errorFileAppears(resultDir));
        String error = readError(resultDir);
        assertTrue(error.contains("malformed") && error.contains("not-a-number"),
            "A non-numeric job_status must be reported as malformed, was: " + error);
        assertFalse(error.contains("Failed to communicate"),
            "A malformed status body is a builder fault, not a communication failure");
        assertFalse(resultDir.resolve(BuilderConstants.BUILD_RESULT_FILENAME).toFile().exists());
    }

    // A runaway malformed body (e.g. an HTML error page) is truncated before it is echoed into
    // error.txt, so it can never bloat the file the client downloads.
    @Test
    @Timeout(60)
    public void overlongMalformedJobStatusBodyIsTruncatedInErrorFile() throws Exception {
        String longBody = "garbage-" + "y".repeat(400);
        builderMock.stubFor(post(urlPathMatching("/build_async/.*"))
                .willReturn(aResponse().withStatus(200).withBody("truncate-job")));
        builderMock.stubFor(get(urlPathEqualTo("/job_status"))
                .willReturn(aResponse().withStatus(200).withBody(longBody)));

        Path resultDir = submitBuild(createBuilder(1), "truncate");

        assertTrue(errorFileAppears(resultDir));
        String error = readError(resultDir);
        assertTrue(error.contains("malformed"), "An unparseable status body must be reported as malformed");
        assertTrue(error.contains("..."), "An overlong malformed body must be truncated with an ellipsis");
        assertFalse(error.contains(longBody), "The full runaway body must not be echoed into error.txt");
    }

    // The job never reached a terminal status: write the timeout diagnostic and do NOT fetch the
    // result (its empty body used to truncate the diagnostic to zero bytes).
    @Test
    @Timeout(60)
    public void buildTimesOutWritesTimeoutDiagnosticWithoutFetchingResult() throws Exception {
        builderMock.stubFor(post(urlPathMatching("/build_async/.*"))
                .willReturn(aResponse().withStatus(200).withBody("timeout-job")));
        builderMock.stubFor(get(urlPathEqualTo("/job_status"))
                .willReturn(aResponse().withStatus(200)
                        .withBody(String.valueOf(BuilderConstants.JobStatus.NOT_FOUND.ordinal()))));

        // Short wait timeout so the poll loop gives up quickly (sleep 100ms, wait 300ms).
        Path resultDir = submitBuild(createBuilder(1, 300), "timeout");

        assertTrue(errorFileAppears(resultDir));
        String error = readError(resultDir);
        assertTrue(error.contains("cannot be defined") && error.contains("timeout-job"),
            "Timeout diagnostic should name the job, was: " + error);
        assertFalse(error.isEmpty(), "The timeout diagnostic must not be truncated to an empty file");
        assertFalse(resultDir.resolve(BuilderConstants.BUILD_RESULT_FILENAME).toFile().exists());
        assertTrue(builderMock.findAll(getRequestedFor(urlPathEqualTo("/job_result"))).isEmpty(),
            "The result must never be fetched for a job that timed out");
    }

    // A terminal ERROR status delivers the builder's compiler output into error.txt.
    @Test
    @Timeout(60)
    public void errorStatusWritesCompilerOutputToErrorFile() throws Exception {
        builderMock.stubFor(post(urlPathMatching("/build_async/.*"))
                .willReturn(aResponse().withStatus(200).withBody("error-status-job")));
        builderMock.stubFor(get(urlPathEqualTo("/job_status"))
                .willReturn(aResponse().withStatus(200)
                        .withBody(String.valueOf(BuilderConstants.JobStatus.ERROR.ordinal()))));
        builderMock.stubFor(get(urlPathEqualTo("/job_result"))
                .willReturn(aResponse().withStatus(200).withBody("main.cpp:12: error: expected ';'")));

        Path resultDir = submitBuild(createBuilder(1), "error-status");

        assertTrue(errorFileAppears(resultDir));
        assertEquals("main.cpp:12: error: expected ';'", readError(resultDir));
        assertFalse(resultDir.resolve(BuilderConstants.BUILD_RESULT_FILENAME).toFile().exists());
    }

    // Keeping a suspendable instance awake is best-effort: a failing touchInstance is swallowed so
    // it never fails an otherwise successful build.
    @Test
    @Timeout(60)
    public void touchInstanceFailureDoesNotAbortTheBuild() throws Exception {
        stubSuccessfulJobPipeline("touch-fail-job");
        builderMock.stubFor(get(urlPathEqualTo("/job_result"))
                .willReturn(aResponse().withStatus(200).withBody(new byte[] {0x50, 0x4b, 0x03, 0x04})));

        GCPInstanceService instanceService = mock(GCPInstanceService.class);
        doThrow(new TimeoutException("touch timed out")).when(instanceService).touchInstance(anyString());
        RemoteEngineBuilder builder = createBuilder(Optional.of(instanceService), 1, BUILD_RESULT_WAIT_TIMEOUT);

        Path resultDir = submitBuild(builder, "touchfail");

        Path buildResult = resultDir.resolve(BuilderConstants.BUILD_RESULT_FILENAME);
        assertTrue(waitFor(() -> buildResult.toFile().exists(), 15_000),
            "A failing touchInstance must be swallowed, not abort the build");
        verify(instanceService, atLeastOnce()).touchInstance(anyString());
    }

    // buildHttpEntity is exercised on the happy path of every build; #974 left it untested. These
    // also guard the try-with-resources that closes the Files.walk directory stream.
    @Test
    public void buildHttpEntityArchivesRegularFilesWithRelativePaths() throws Exception {
        Path project = Files.createDirectories(tmpDir.resolve("entity-happy").resolve("upload"));
        Files.writeString(project.resolve("a.txt"), "alpha");
        Files.createDirectories(project.resolve("sub").resolve("deep"));
        Files.writeString(project.resolve("sub").resolve("b.txt"), "bravo");
        Files.writeString(project.resolve("sub").resolve("deep").resolve("c.txt"), "charlie");

        File archive = tmpDir.resolve("entity-happy-archive.zip").toFile();
        HttpEntity entity = createBuilder(1).buildHttpEntity(project.toFile(), archive);

        assertNotNull(entity);
        assertEquals(Set.of("a.txt", "sub/b.txt", "sub/deep/c.txt"), zipEntryNames(archive));
        assertArrayEquals("bravo".getBytes(StandardCharsets.UTF_8), zipEntryBytes(archive, "sub/b.txt"));
    }

    @Test
    public void buildHttpEntityExcludesMagicFilesAtAnyDepth() throws Exception {
        Path project = Files.createDirectories(tmpDir.resolve("entity-magic").resolve("upload"));
        Files.writeString(project.resolve("keep.txt"), "keep");
        Files.writeString(project.resolve(ExtenderConst.SOURCE_CODE_ARCHIVE_MAGIC_NAME), "x");
        Files.writeString(project.resolve(DataCacheService.FILE_CACHE_INFO_FILE), "x");
        Path sub = Files.createDirectories(project.resolve("sub"));
        Files.writeString(sub.resolve(ExtenderConst.SOURCE_CODE_ARCHIVE_MAGIC_NAME), "x");
        Files.writeString(sub.resolve(DataCacheService.FILE_CACHE_INFO_FILE), "x");

        File archive = tmpDir.resolve("entity-magic-archive.zip").toFile();
        createBuilder(1).buildHttpEntity(project.toFile(), archive);

        assertEquals(Set.of("keep.txt"), zipEntryNames(archive));
    }

    @Test
    public void buildHttpEntitySkipsUnreadableFileButKeepsOthers() throws Exception {
        Path project = Files.createDirectories(tmpDir.resolve("entity-unreadable").resolve("upload"));
        Files.writeString(project.resolve("readable.txt"), "ok");
        File blocked = Files.writeString(project.resolve("blocked.txt"), "secret").toFile();
        assumeTrue(blocked.setReadable(false, false) && !blocked.canRead(),
            "Filesystem ignores the read-permission bit (e.g. running as root); skipping");

        File archive = tmpDir.resolve("entity-unreadable-archive.zip").toFile();
        createBuilder(1).buildHttpEntity(project.toFile(), archive);

        // The unreadable file is swallowed with a LOGGER.warn and the build proceeds without it.
        assertEquals(Set.of("readable.txt"), zipEntryNames(archive));
    }

    @Test
    public void buildHttpEntityOnMissingProjectDirectoryThrowsExtenderException() {
        File missing = tmpDir.resolve("does-not-exist").toFile();
        File archive = tmpDir.resolve("missing-archive.zip").toFile();

        // Files.walk on a missing directory fails; buildHttpEntity wraps it so buildAsync can
        // surface it as a RemoteBuildException instead of leaking a raw IOException.
        assertThrows(ExtenderException.class,
            () -> createBuilder(1).buildHttpEntity(missing, archive));
    }

    private boolean errorFileAppears(Path resultDir) throws InterruptedException {
        return waitFor(() -> resultDir.resolve(BuilderConstants.BUILD_ERROR_FILENAME).toFile().exists(), 15_000);
    }

    private static String readError(Path resultDir) throws Exception {
        return Files.readString(resultDir.resolve(BuilderConstants.BUILD_ERROR_FILENAME));
    }

    private static Set<String> zipEntryNames(File archive) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipFile zf = new ZipFile(archive)) {
            zf.stream().forEach(entry -> names.add(entry.getName().replace('\\', '/')));
        }
        return names;
    }

    private static byte[] zipEntryBytes(File archive, String name) throws Exception {
        try (ZipFile zf = new ZipFile(archive)) {
            ZipEntry entry = zf.getEntry(name);
            assertNotNull(entry, "archive is missing entry " + name);
            try (InputStream in = zf.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }
}
