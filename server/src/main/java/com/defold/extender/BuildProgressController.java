package com.defold.extender;

import com.defold.extender.progress.BuildProgressService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;

/**
 * Streams live build progress as Server-Sent Events. Advisory only:
 * clients must keep polling /job_status for completion. Old clients never
 * call this endpoint; new clients treat a 404 (old server or feature
 * disabled) as "no progress available" and fall back to polling.
 */
@RestController
public class BuildProgressController {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildProgressController.class);

    private final BuildProgressService progressService;
    private final File jobResultLocation;

    public BuildProgressController(BuildProgressService progressService,
                                   @Value("${extender.job-result.location}") String jobResultLocation) {
        this.progressService = progressService;
        this.jobResultLocation = new File(jobResultLocation);
    }

    @GetMapping(path = "/job_progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> jobProgress(@RequestParam(name = "jobId") String jobId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader)
            throws IOException {
        if (!progressService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        SseEmitter emitter;
        try {
            emitter = progressService.subscribe(jobId, parseLastEventId(lastEventIdHeader));
        } catch (IllegalStateException e) {
            LOGGER.warn(e.getMessage());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        if (emitter != null) {
            return ResponseEntity.ok(emitter);
        }

        // Not a live job: if the result files are already on disk (subscribed
        // after the build finished, or the server restarted), send a single
        // terminal event so the subscriber is not left hanging.
        File jobResultDir;
        try {
            jobResultDir = SandboxedPath.resolve(jobResultLocation, jobId);
        } catch (ExtenderException e) {
            return ResponseEntity.notFound().build();
        }
        if (jobResultDir.exists()) {
            if (new File(jobResultDir, BuilderConstants.BUILD_RESULT_FILENAME).exists()) {
                return ResponseEntity.ok(progressService.finishedJobEmitter(jobId, true));
            }
            if (new File(jobResultDir, BuilderConstants.BUILD_ERROR_FILENAME).exists()) {
                return ResponseEntity.ok(progressService.finishedJobEmitter(jobId, false));
            }
        }
        return ResponseEntity.notFound().build();
    }

    private static Long parseLastEventId(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Long.valueOf(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
