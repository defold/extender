package com.defold.extender.progress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory registry of per-job build progress. Producers (controller,
 * AsyncBuilder, Extender) report through the {@link ProgressReporter}
 * returned by {@link #register}/{@link #reporterFor}; consumers subscribe
 * with {@link #subscribe} and receive JSON events over SSE.
 *
 * Entries live from register() until the terminal event (or the TTL sweep
 * for builds that died without one). SSE is advisory: job completion is
 * still determined by /job_status, and terminal events are only emitted
 * after the result files are in place.
 */
@Service
public class BuildProgressService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildProgressService.class);

    private static final int MAX_DETAIL_LENGTH = 512;
    public static final String EVENT_NAME = "progress";

    private final boolean enabled;
    private final long sseTimeout;
    private final int eventBufferSize;
    private final int maxSubscribersPerJob;
    private final long registryTtl;

    private final ConcurrentHashMap<String, JobProgress> jobs = new ConcurrentHashMap<>();

    public BuildProgressService(@Value("${extender.progress.enabled:true}") boolean enabled,
                                @Value("${extender.progress.sse-timeout:1800000}") long sseTimeout,
                                @Value("${extender.progress.event-buffer-size:256}") int eventBufferSize,
                                @Value("${extender.progress.max-subscribers-per-job:8}") int maxSubscribersPerJob,
                                @Value("${extender.progress.registry-ttl:1200000}") long registryTtl) {
        this.enabled = enabled;
        this.sseTimeout = sseTimeout;
        this.eventBufferSize = eventBufferSize;
        this.maxSubscribersPerJob = maxSubscribersPerJob;
        this.registryTtl = registryTtl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Create the progress entry for a job. Must be called before the async
     * build is dispatched so that reporterFor() finds it on the worker thread.
     */
    public ProgressReporter register(String jobId) {
        if (!enabled) {
            return ProgressReporter.NOOP;
        }
        return jobs.computeIfAbsent(jobId, JobProgress::new);
    }

    /** Reporter for an already registered job, or NOOP if unknown/disabled. */
    public ProgressReporter reporterFor(String jobId) {
        JobProgress job = jobs.get(jobId);
        return job != null ? job : ProgressReporter.NOOP;
    }

    /** Drop a job that never started building (e.g. failed dispatch). */
    public void remove(String jobId) {
        JobProgress job = jobs.remove(jobId);
        if (job != null) {
            job.completeEmitters();
        }
    }

    /**
     * Subscribe to a live job. Returns null when the job is unknown.
     * Throws IllegalStateException when the job has too many subscribers.
     */
    public SseEmitter subscribe(String jobId, Long lastEventId) {
        JobProgress job = jobs.get(jobId);
        if (job == null) {
            return null;
        }
        return job.subscribe(lastEventId);
    }

    /**
     * Republish an event relayed from a remote builder under the frontend's
     * local jobId. The event is re-sequenced locally.
     */
    public void publishRaw(String jobId, BuildStage stage, String detail, int percent,
                           String extension, Integer currentFile, Integer totalFiles) {
        JobProgress job = jobs.get(jobId);
        if (job != null) {
            job.publish(stage, detail, percent, extension, currentFile, totalFiles);
        }
    }

    /**
     * One-shot emitter for a job that already has its result files on disk:
     * sends a single terminal event and completes.
     */
    public SseEmitter finishedJobEmitter(String jobId, boolean success) {
        SseEmitter emitter = new SseEmitter(sseTimeout);
        BuildStage stage = success ? BuildStage.SUCCESS : BuildStage.ERROR;
        BuildProgressEvent event = new BuildProgressEvent(jobId, 0, System.currentTimeMillis(),
                stage, "Build " + (success ? "succeeded" : "failed"), 100, null, null, null);
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.getSeq()))
                    .name(EVENT_NAME)
                    .data(event, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @Scheduled(fixedDelayString = "${extender.progress.heartbeat-interval:15000}")
    public void sendHeartbeats() {
        for (JobProgress job : jobs.values()) {
            job.heartbeat();
        }
    }

    @Scheduled(fixedDelayString = "${extender.progress.cleanup-period:20000}")
    public void cleanStaleJobs() {
        long deadline = System.currentTimeMillis() - registryTtl;
        for (Map.Entry<String, JobProgress> entry : jobs.entrySet()) {
            if (entry.getValue().lastTouched < deadline) {
                LOGGER.warn("Removing stale progress entry for job {}", entry.getKey());
                remove(entry.getKey());
            }
        }
    }

    private static String truncate(String detail) {
        if (detail != null && detail.length() > MAX_DETAIL_LENGTH) {
            return detail.substring(0, MAX_DETAIL_LENGTH);
        }
        return detail;
    }

    // Start/end of the percent budget for each stage. COMPILING is
    // interpolated by aggregate file counters; other stages report their
    // start value. The global percent is clamped non-decreasing because
    // file totals grow while extensions are discovered sequentially.
    private static int stageStartPercent(BuildStage stage) {
        switch (stage) {
            case RECEIVED:
            case QUEUED:          return 0;
            case SDK:             return 1;
            case DEPENDENCIES:    return 10;
            case MANIFESTS:       return 20;
            case PLATFORM:        return 25;
            case COMPILING:       return 35;
            case REMOTE_BUILDING: return 35;
            case LINKING:         return 80;
            case PACKAGING:       return 92;
            case SUCCESS:
            case ERROR:           return 100;
            default:              return 0;
        }
    }

    private static final int COMPILING_START = 35;
    private static final int COMPILING_END = 80;

    private static class ExtensionCounter {
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
    }

    private class JobProgress implements ProgressReporter {
        private final String jobId;
        private final Object lock = new Object();
        private final AtomicLong seq = new AtomicLong();
        private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private final Deque<BuildProgressEvent> buffer = new ArrayDeque<>();
        private final Map<String, ExtensionCounter> extensionCounters = new ConcurrentHashMap<>();
        private final AtomicInteger jobTotalFiles = new AtomicInteger();
        private final AtomicInteger jobCompletedFiles = new AtomicInteger();
        private volatile int lastPercent = 0;
        private boolean terminated = false;
        private volatile BuildProgressEvent lastEvent;
        private volatile long lastTouched = System.currentTimeMillis();

        JobProgress(String jobId) {
            this.jobId = jobId;
        }

        @Override
        public void stage(BuildStage stage, String detail) {
            publish(stage, detail, stageStartPercent(stage), null, null, null);
        }

        @Override
        public void compileBatchBegin(String extension, int totalFiles) {
            ExtensionCounter counter = extensionCounters.computeIfAbsent(extension, k -> new ExtensionCounter());
            // increment and publish atomically so counters in the event
            // stream never go backwards (per-file callbacks are concurrent)
            synchronized (lock) {
                int extTotal = counter.total.addAndGet(totalFiles);
                jobTotalFiles.addAndGet(totalFiles);
                publish(BuildStage.COMPILING, extension + ": compiling source files", compilingPercent(),
                        extension, counter.done.get(), extTotal);
            }
        }

        @Override
        public void fileCompiled(String extension) {
            ExtensionCounter counter = extensionCounters.computeIfAbsent(extension, k -> new ExtensionCounter());
            synchronized (lock) {
                int extDone = counter.done.incrementAndGet();
                jobCompletedFiles.incrementAndGet();
                publish(BuildStage.COMPILING, extension + ": compiling source files", compilingPercent(),
                        extension, extDone, counter.total.get());
            }
        }

        @Override
        public void terminal(boolean success, String detail) {
            // a build reports its outcome from several exit paths; only the
            // first one wins, so the most specific reason is what subscribers see
            synchronized (lock) {
                if (terminated) {
                    return;
                }
                terminated = true;
                publish(success ? BuildStage.SUCCESS : BuildStage.ERROR, detail, 100, null, null, null);
            }
            completeEmitters();
            jobs.remove(jobId);
        }

        private int compilingPercent() {
            int total = jobTotalFiles.get();
            if (total <= 0) {
                return COMPILING_START;
            }
            int done = Math.min(jobCompletedFiles.get(), total);
            return COMPILING_START + (COMPILING_END - COMPILING_START) * done / total;
        }

        void publish(BuildStage stage, String detail, int percent,
                     String extension, Integer currentFile, Integer totalFiles) {
            synchronized (lock) {
                // never let the reported percent regress
                int clamped = Math.max(lastPercent, percent);
                lastPercent = clamped;
                BuildProgressEvent event = new BuildProgressEvent(jobId, seq.incrementAndGet(),
                        System.currentTimeMillis(), stage, truncate(detail), clamped,
                        extension, currentFile, totalFiles);
                lastEvent = event;
                lastTouched = event.getTs();
                buffer.addLast(event);
                while (buffer.size() > eventBufferSize) {
                    buffer.removeFirst();
                }
                for (SseEmitter emitter : emitters) {
                    sendEvent(emitter, event);
                }
            }
        }

        SseEmitter subscribe(Long lastEventId) {
            SseEmitter emitter = new SseEmitter(sseTimeout);
            emitter.onCompletion(() -> emitters.remove(emitter));
            emitter.onTimeout(() -> emitters.remove(emitter));
            emitter.onError(e -> emitters.remove(emitter));
            synchronized (lock) {
                if (emitters.size() >= maxSubscribersPerJob) {
                    throw new IllegalStateException(String.format("Too many progress subscribers for job %s", jobId));
                }
                emitters.add(emitter);
                // Catch the subscriber up while holding the lock so no live
                // event can interleave. If we can replay the exact gap since
                // lastEventId, do so; otherwise a snapshot of the current
                // state is sufficient (early events precede any subscriber:
                // the build is dispatched before the jobId is returned).
                List<BuildProgressEvent> replay = replayableTail(lastEventId);
                if (replay != null) {
                    for (BuildProgressEvent event : replay) {
                        sendEvent(emitter, event);
                    }
                } else if (lastEvent != null) {
                    sendEvent(emitter, lastEvent);
                }
            }
            return emitter;
        }

        /**
         * Buffered events with seq > lastEventId, or null when the gap
         * cannot be covered (no id given, or the buffer no longer reaches
         * back that far) and a snapshot must be sent instead.
         */
        private List<BuildProgressEvent> replayableTail(Long lastEventId) {
            if (lastEventId == null || buffer.isEmpty()) {
                return null;
            }
            if (buffer.peekFirst().getSeq() > lastEventId + 1) {
                return null;
            }
            List<BuildProgressEvent> tail = new ArrayList<>();
            for (BuildProgressEvent event : buffer) {
                if (event.getSeq() > lastEventId) {
                    tail.add(event);
                }
            }
            return tail;
        }

        private void sendEvent(SseEmitter emitter, BuildProgressEvent event) {
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.getSeq()))
                        .name(EVENT_NAME)
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // dead client; not a server error
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }

        void heartbeat() {
            synchronized (lock) {
                for (SseEmitter emitter : emitters) {
                    try {
                        emitter.send(SseEmitter.event().comment("ka"));
                    } catch (IOException | IllegalStateException e) {
                        emitters.remove(emitter);
                        emitter.completeWithError(e);
                    }
                }
            }
        }

        void completeEmitters() {
            synchronized (lock) {
                for (SseEmitter emitter : emitters) {
                    try {
                        emitter.complete();
                    } catch (IllegalStateException e) {
                        // already completed
                    }
                }
                emitters.clear();
            }
        }
    }
}
