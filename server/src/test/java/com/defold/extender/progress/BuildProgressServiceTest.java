package com.defold.extender.progress;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BuildProgressServiceTest {

    private static BuildProgressService createService() {
        return new BuildProgressService(true, 30000, 256, 8, 1200000);
    }

    @Test
    public void testDisabledServiceReturnsNoop() {
        BuildProgressService service = new BuildProgressService(false, 30000, 256, 8, 1200000);
        assertSame(ProgressReporter.NOOP, service.register("job1"));
        assertSame(ProgressReporter.NOOP, service.reporterFor("job1"));
        assertNull(service.subscribe("job1", null));
    }

    @Test
    public void testRegisterReturnsSameReporter() {
        BuildProgressService service = createService();
        ProgressReporter reporter = service.register("job1");
        assertNotSame(ProgressReporter.NOOP, reporter);
        assertSame(reporter, service.register("job1"));
        assertSame(reporter, service.reporterFor("job1"));
    }

    @Test
    public void testUnknownJobReturnsNoopAndNullSubscription() {
        BuildProgressService service = createService();
        assertSame(ProgressReporter.NOOP, service.reporterFor("unknown"));
        assertNull(service.subscribe("unknown", null));
    }

    @Test
    public void testRemoveDropsJob() {
        BuildProgressService service = createService();
        service.register("job1");
        assertNotNull(service.subscribe("job1", null));
        service.remove("job1");
        assertNull(service.subscribe("job1", null));
    }

    @Test
    public void testTerminalRemovesJob() {
        BuildProgressService service = createService();
        ProgressReporter reporter = service.register("job1");
        reporter.stage(BuildStage.SDK, "sdk");
        reporter.terminal(true, "done");
        assertNull(service.subscribe("job1", null));
        // late reports after terminal must be harmless no-ops
        service.publishRaw("job1", BuildStage.COMPILING, "late", 50, null, null, null);
    }

    @Test
    public void testSubscriberLimit() {
        BuildProgressService service = new BuildProgressService(true, 30000, 256, 2, 1200000);
        service.register("job1");
        assertNotNull(service.subscribe("job1", null));
        assertNotNull(service.subscribe("job1", null));
        assertThrows(IllegalStateException.class, () -> service.subscribe("job1", null));
    }

    @Test
    public void testStaleJobSweep() throws InterruptedException {
        // ttl 0: everything is stale as soon as it is a millisecond old
        BuildProgressService service = new BuildProgressService(true, 30000, 256, 8, 0);
        service.register("job1");
        Thread.sleep(5);
        service.cleanStaleJobs();
        assertNull(service.subscribe("job1", null));
    }

    @Test
    public void testHeartbeatWithSubscribers() {
        BuildProgressService service = createService();
        service.register("job1");
        SseEmitter emitter = service.subscribe("job1", null);
        assertNotNull(emitter);
        // must not throw with live (uninitialized) emitters
        service.sendHeartbeats();
    }
}
