package com.defold.extender.remote;

import com.defold.extender.progress.BuildProgressService;
import com.defold.extender.progress.BuildStage;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Relays SSE build-progress events from a remote builder into the
 * frontend's local progress registry, rewriting the remote jobId to the
 * frontend's local jobId (events are re-sequenced by the registry).
 *
 * Runs on a dedicated daemon thread (never on the build executor: a
 * blocked relay must not starve build workers). Uses its own single-use
 * HTTP client so the long-lived stream cannot exhaust the shared client's
 * connection pool.
 *
 * The remote terminal event is suppressed: the frontend emits its own
 * terminal only after the result file has landed locally, keeping
 * /job_status consistent for anyone reacting to the terminal event.
 *
 * If the remote builder runs an older server version (404) the relay
 * gives up silently; RemoteEngineBuilder's poll loop then synthesizes
 * coarse REMOTE_BUILDING ticks instead (see isDeliveringEvents()).
 */
public class RemoteProgressRelay implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteProgressRelay.class);

    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_BACKOFF_MS = 2000;

    private final String remoteUrl;
    private final String remoteJobId;
    private final String localJobId;
    private final BuildProgressService progressService;

    private volatile boolean stopped = false;
    private volatile boolean streaming = false;
    private volatile HttpGet currentRequest = null;
    private long lastEventId = -1;

    public RemoteProgressRelay(String remoteUrl, String remoteJobId, String localJobId,
                               BuildProgressService progressService) {
        this.remoteUrl = remoteUrl;
        this.remoteJobId = remoteJobId;
        this.localJobId = localJobId;
        this.progressService = progressService;
    }

    /** True while events are flowing from the remote builder. */
    public boolean isDeliveringEvents() {
        return streaming;
    }

    public void stop() {
        stopped = true;
        HttpGet request = currentRequest;
        if (request != null) {
            request.abort();
        }
    }

    @Override
    public void run() {
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            int attempts = 0;
            while (!stopped && attempts < MAX_RECONNECT_ATTEMPTS) {
                attempts++;
                try {
                    if (stream(client)) {
                        return; // unsupported by remote, or terminal seen
                    }
                } catch (IOException e) {
                    if (stopped) {
                        return;
                    }
                    LOGGER.info("Progress relay for job {} dropped ({}), reconnecting", localJobId, e.getMessage());
                } finally {
                    streaming = false;
                }
                Thread.sleep(RECONNECT_BACKOFF_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Progress relay for job {} failed", localJobId, e);
        } finally {
            streaming = false;
        }
    }

    /**
     * Opens the SSE stream and relays events as they arrive.
     * Returns true when the relay is done for good (remote has no progress
     * support, or a terminal event arrived); false to reconnect.
     */
    private boolean stream(CloseableHttpClient client) throws IOException {
        HttpGet request = new HttpGet(String.format("%s/job_progress?jobId=%s", remoteUrl, remoteJobId));
        request.setHeader("Accept", "text/event-stream");
        if (lastEventId >= 0) {
            request.setHeader("Last-Event-ID", Long.toString(lastEventId));
        }
        // the stream is expected to stay open for the whole build; rely on
        // the remote's heartbeats and stop() instead of a socket timeout
        request.setConfig(RequestConfig.custom().setSocketTimeout(0).build());
        currentRequest = request;
        try {
            HttpResponse response = client.execute(request);
            int status = response.getStatusLine().getStatusCode();
            if (status != HttpStatus.SC_OK) {
                EntityUtils.consumeQuietly(response.getEntity());
                if (status == HttpStatus.SC_NOT_FOUND || status == HttpStatus.SC_METHOD_NOT_ALLOWED) {
                    LOGGER.info("Remote builder at {} does not expose build progress, using coarse fallback", remoteUrl);
                    return true;
                }
                LOGGER.info("Progress relay for job {} got status {}", localJobId, status);
                return false; // transient; retry
            }
            streaming = true;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                String eventId = null;
                StringBuilder data = new StringBuilder();
                String line;
                while (!stopped && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        // end of one SSE event
                        if (data.length() > 0 && dispatch(eventId, data.toString())) {
                            return true; // terminal seen, done
                        }
                        eventId = null;
                        data.setLength(0);
                    } else if (line.startsWith("id:")) {
                        eventId = line.substring(3).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    }
                    // "event:" names and ":" comments (heartbeats) are ignored
                }
            }
            return stopped; // EOF mid-build: reconnect
        } finally {
            currentRequest = null;
        }
    }

    /** Relays one event. Returns true when it was a terminal event. */
    private boolean dispatch(String eventId, String data) {
        try {
            JSONObject json = (JSONObject) new JSONParser().parse(data);
            if (eventId != null) {
                lastEventId = Long.parseLong(eventId);
            }
            BuildStage stage = BuildStage.valueOf((String) json.get("stage"));
            if (stage.isTerminal()) {
                // suppressed: the frontend emits its own terminal event
                // after the result file lands locally
                return true;
            }
            Number percent = (Number) json.get("percent");
            Number currentFile = (Number) json.get("currentFile");
            Number totalFiles = (Number) json.get("totalFiles");
            progressService.publishRaw(localJobId, stage, (String) json.get("detail"),
                    percent != null ? percent.intValue() : 0,
                    (String) json.get("extension"),
                    currentFile != null ? currentFile.intValue() : null,
                    totalFiles != null ? totalFiles.intValue() : null);
        } catch (Exception e) {
            LOGGER.debug("Ignoring malformed progress event for job {}: {}", localJobId, e.getMessage());
        }
        return false;
    }
}
