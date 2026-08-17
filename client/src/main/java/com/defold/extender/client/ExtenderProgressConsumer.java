package com.defold.extender.client;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Consumes the server's /job_progress SSE stream on a background thread
 * and forwards events to an ExtenderProgressListener.
 *
 * Strictly advisory: any failure (404 from an old server, dropped
 * connection, malformed data) is swallowed after bounded reconnect
 * attempts. The poll loop in ExtenderClient.build_async remains the sole
 * authority on build completion and calls stop() when the build is done.
 */
class ExtenderProgressConsumer implements Runnable {
    private static final Logger logger = Logger.getLogger(ExtenderProgressConsumer.class.getName());

    /** Creates GET requests carrying the client's auth and custom headers. */
    interface GetRequestFactory {
        HttpGet create(String url) throws IOException;
    }

    private static final long RECONNECT_BACKOFF_MS = 2000;
    private static final String RECONNECT_ATTEMPTS_PROPERTY = "com.defold.extender.client.progress-reconnect-attempts";
    private static final int DEFAULT_RECONNECT_ATTEMPTS = 5;

    private final HttpClient httpClient;
    private final String jobProgressUrl;
    private final GetRequestFactory requestFactory;
    private final ExtenderProgressListener listener;
    private final int maxReconnectAttempts;

    private volatile boolean stopped = false;
    private volatile HttpGet currentRequest = null;
    private long lastEventId = -1;

    ExtenderProgressConsumer(HttpClient httpClient, String extenderBaseUrl, String jobId,
                             GetRequestFactory requestFactory, ExtenderProgressListener listener) {
        this.httpClient = httpClient;
        this.jobProgressUrl = String.format("%s/job_progress?jobId=%s", extenderBaseUrl, jobId);
        this.requestFactory = requestFactory;
        this.listener = listener;
        this.maxReconnectAttempts = resolveMaxReconnectAttempts();
    }

    /**
     * Reads the reconnect-attempts override from a system property, falling back
     * to the default when the property is absent or not a valid integer.
     */
    private static int resolveMaxReconnectAttempts() {
        String value = System.getProperty(RECONNECT_ATTEMPTS_PROPERTY);
        if (value == null) {
            return DEFAULT_RECONNECT_ATTEMPTS;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.log(Level.WARNING, "Ignoring malformed {0} value ''{1}''; using default {2}",
                    new Object[] { RECONNECT_ATTEMPTS_PROPERTY, value, DEFAULT_RECONNECT_ATTEMPTS });
            return DEFAULT_RECONNECT_ATTEMPTS;
        }
    }

    /** Stops the consumer and unblocks the stream read. Safe to call more than once. */
    void stop() {
        stopped = true;
        HttpGet request = currentRequest;
        if (request != null) {
            request.abort();
        }
    }

    @Override
    public void run() {
        try {
            int attempts = 0;
            while (!stopped && attempts < maxReconnectAttempts) {
                attempts++;
                try {
                    if (stream()) {
                        return; // unsupported by server or terminal event seen
                    }
                } catch (IOException e) {
                    if (stopped) {
                        return;
                    }
                    logger.log(Level.FINE, "Build progress stream dropped, reconnecting: " + e.getMessage());
                }
                Thread.sleep(RECONNECT_BACKOFF_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // progress must never break the build
            logger.log(Level.FINE, "Build progress consumer stopped: " + e.getMessage());
        }
    }

    /**
     * Opens the SSE stream and forwards events until it ends.
     * Returns true when the consumer is done for good (server has no
     * progress support, or a terminal event arrived); false to reconnect.
     */
    private boolean stream() throws IOException {
        HttpGet request = requestFactory.create(jobProgressUrl);
        request.setHeader("Accept", "text/event-stream");
        if (lastEventId >= 0) {
            request.setHeader("Last-Event-ID", Long.toString(lastEventId));
        }
        // the stream stays open for the whole build: disable the socket
        // read timeout for this request; stop() aborts it when the build is done
        request.setConfig(RequestConfig.custom().setSocketTimeout(0).build());
        currentRequest = request;
        try {
            HttpResponse response = httpClient.execute(request);
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                // old server or progress disabled; polling still reports completion
                EntityUtils.consumeQuietly(response.getEntity());
                return true;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                String eventId = null;
                StringBuilder data = new StringBuilder();
                String line;
                while (!stopped && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        // end of one SSE event
                        if (data.length() > 0 && dispatch(eventId, data.toString())) {
                            return true; // terminal event
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
            return stopped;
        } finally {
            currentRequest = null;
        }
    }

    /** Forwards one event to the listener. Returns true for terminal events. */
    private boolean dispatch(String eventId, String data) {
        boolean terminal = false;
        try {
            JSONObject json = (JSONObject) new JSONParser().parse(data);
            if (eventId != null) {
                lastEventId = Long.parseLong(eventId);
            }
            String stage = (String) json.get("stage");
            String detail = (String) json.get("detail");
            Number percent = (Number) json.get("percent");
            Number currentFile = (Number) json.get("currentFile");
            Number totalFiles = (Number) json.get("totalFiles");
            Boolean isTerminal = (Boolean) json.get("terminal");
            terminal = isTerminal != null && isTerminal;
            listener.onProgress(stage, detail,
                    percent != null ? percent.intValue() : 0,
                    currentFile != null ? currentFile.intValue() : -1,
                    totalFiles != null ? totalFiles.intValue() : -1);
        } catch (Exception e) {
            // a malformed event or a listener bug must not kill the stream
            logger.log(Level.FINE, "Ignoring bad progress event: " + e.getMessage());
        }
        return terminal;
    }
}
