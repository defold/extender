package com.defold.extender;

import com.defold.extender.progress.BuildProgressService;
import com.defold.extender.progress.BuildStage;
import com.defold.extender.progress.ProgressReporter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class BuildProgressControllerTest {

    @TempDir
    File jobResultLocation;

    private BuildProgressService service;
    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        service = new BuildProgressService(true, 30000, 256, 8, 1200000);
        mockMvc = mockMvcFor(service);
    }

    private MockMvc mockMvcFor(BuildProgressService service) {
        BuildProgressController controller = new BuildProgressController(service, jobResultLocation.getAbsolutePath());
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private MvcResult subscribe(String jobId) throws Exception {
        return mockMvc.perform(get("/job_progress").param("jobId", jobId))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static List<Long> eventIds(String sseContent) {
        List<Long> ids = new ArrayList<>();
        Matcher matcher = Pattern.compile("^id:(\\d+)$", Pattern.MULTILINE).matcher(sseContent);
        while (matcher.find()) {
            ids.add(Long.parseLong(matcher.group(1)));
        }
        return ids;
    }

    @Test
    public void testSnapshotAndLiveEvents() throws Exception {
        ProgressReporter reporter = service.register("job1");
        reporter.stage(BuildStage.SDK, "Downloading Defold SDK");

        MvcResult result = subscribe("job1");
        String content = result.getResponse().getContentAsString();
        // snapshot of the current state arrives immediately
        assertTrue(content.contains("\"stage\":\"SDK\""));

        reporter.stage(BuildStage.COMPILING, "extension1");
        reporter.terminal(true, "Build succeeded");

        content = result.getResponse().getContentAsString();
        assertTrue(content.contains("\"stage\":\"COMPILING\""));
        assertTrue(content.contains("\"stage\":\"SUCCESS\""));
        assertTrue(content.contains("\"terminal\":true"));
        assertTrue(content.contains("\"percent\":100"));

        // seq strictly increasing
        List<Long> ids = eventIds(content);
        assertFalse(ids.isEmpty());
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i) > ids.get(i - 1), "event ids must be strictly increasing");
        }
    }

    @Test
    public void testOnlyFirstTerminalIsReported() throws Exception {
        // RemoteEngineBuilder reports the outcome from several exit paths (a specific
        // one plus a catch-all in its finally block); subscribers must see exactly one
        // terminal event, carrying the most specific reason.
        ProgressReporter reporter = service.register("job1");
        MvcResult result = subscribe("job1");

        reporter.terminal(false, "Remote build timed out");
        reporter.terminal(false, "Build failed");

        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("Remote build timed out"));
        assertFalse(content.contains("Build failed"));
        assertEquals(1, content.split("\"terminal\":true", -1).length - 1,
                "exactly one terminal event must be emitted");
    }

    @Test
    public void testLastEventIdReplay() throws Exception {
        ProgressReporter reporter = service.register("job1");
        reporter.stage(BuildStage.SDK, "one");          // seq 1
        reporter.stage(BuildStage.DEPENDENCIES, "two"); // seq 2
        reporter.stage(BuildStage.MANIFESTS, "three");  // seq 3
        reporter.stage(BuildStage.PLATFORM, "four");    // seq 4

        MvcResult result = mockMvc.perform(get("/job_progress").param("jobId", "job1")
                        .header("Last-Event-ID", "2"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertEquals(List.of(3L, 4L), eventIds(result.getResponse().getContentAsString()));
    }

    @Test
    public void testSnapshotWhenReplayGapExceedsBuffer() throws Exception {
        // buffer of 2 only holds seq 4 and 5; Last-Event-ID 1 cannot be replayed
        service = new BuildProgressService(true, 30000, 2, 8, 1200000);
        mockMvc = mockMvcFor(service);
        ProgressReporter reporter = service.register("job1");
        for (int i = 0; i < 5; i++) {
            reporter.stage(BuildStage.SDK, "event " + i); // seq 1..5
        }

        MvcResult result = mockMvc.perform(get("/job_progress").param("jobId", "job1")
                        .header("Last-Event-ID", "1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // only the state snapshot (the latest event)
        assertEquals(List.of(5L), eventIds(result.getResponse().getContentAsString()));
    }

    @Test
    public void testConcurrentFileCountingAndPercentClamp() throws Exception {
        ProgressReporter reporter = service.register("job1");
        reporter.stage(BuildStage.COMPILING, "extension1");
        reporter.compileBatchBegin("extension1", 100);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> reporter.fileCompiled("extension1"));
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // all 100 files counted exactly once: compiling budget is exhausted
        MvcResult result = subscribe("job1");
        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("\"currentFile\":100"));
        assertTrue(content.contains("\"totalFiles\":100"));
        assertTrue(content.contains("\"percent\":80"));

        // a late-discovered extension grows the totals; percent must not regress
        reporter.compileBatchBegin("extension2", 100);
        content = result.getResponse().getContentAsString();
        String lastData = content.substring(content.lastIndexOf("data:"));
        assertTrue(lastData.contains("\"extension\":\"extension2\""));
        assertTrue(lastData.contains("\"percent\":80"), "percent must stay clamped at 80, got: " + lastData);
    }

    @Test
    public void testFinishedJobFromResultFiles() throws Exception {
        File jobDir = new File(jobResultLocation, "job42");
        assertTrue(jobDir.mkdir());
        Files.createFile(new File(jobDir, BuilderConstants.BUILD_RESULT_FILENAME).toPath());

        MvcResult result = subscribe("job42");
        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("\"stage\":\"SUCCESS\""));
        assertTrue(content.contains("\"terminal\":true"));
    }

    @Test
    public void testFinishedJobWithErrorFile() throws Exception {
        File jobDir = new File(jobResultLocation, "job43");
        assertTrue(jobDir.mkdir());
        Files.createFile(new File(jobDir, BuilderConstants.BUILD_ERROR_FILENAME).toPath());

        MvcResult result = subscribe("job43");
        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("\"stage\":\"ERROR\""));
        assertTrue(content.contains("\"terminal\":true"));
    }

    @Test
    public void testUnknownJobReturns404() throws Exception {
        mockMvc.perform(get("/job_progress").param("jobId", "nosuchjob"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testMalformedJobIdReturns404() throws Exception {
        mockMvc.perform(get("/job_progress").param("jobId", "../../etc/passwd"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testDisabledReturns404() throws Exception {
        service = new BuildProgressService(false, 30000, 256, 8, 1200000);
        mockMvc = mockMvcFor(service);
        service.register("job1"); // no-op when disabled
        mockMvc.perform(get("/job_progress").param("jobId", "job1"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testTooManySubscribersReturns429() throws Exception {
        service = new BuildProgressService(true, 30000, 256, 1, 1200000);
        mockMvc = mockMvcFor(service);
        service.register("job1");
        subscribe("job1");
        mockMvc.perform(get("/job_progress").param("jobId", "job1"))
                .andExpect(status().isTooManyRequests());
    }
}
