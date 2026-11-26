package com.defold.extender.remote.operator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Touch API bearer token from {@code token-file}, re-read at most every {@link #REFRESH_INTERVAL}.
 *
 * <p>Kubernetes rotates a mounted Secret by swapping a symlink, so a plain periodic re-read picks
 * up the new value; the operator keeps accepting the previous token for {@link #OPERATOR_GRACE}
 * far longer than the refresh interval. If a re-read fails the last good token is
 * kept; once it is older than the grace period the operator rejects it, so that is logged as ERROR.
 * Ages use a monotonic clock, so wall-clock steps (NTP, VM resume) can't stall refreshes.
 *
 * <p>Application Security Requirement: the token never appears in {@link #toString()}, exceptions
 * or logs, is size-capped, and is restricted to RFC 6750 b64token characters so it can't inject
 * header content.
 */
public final class TokenProvider {

    /** Matches the operator's own re-read interval for the same Secret. */
    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(10);
    /** How long the operator keeps accepting the previous token after a rotation. */
    static final Duration OPERATOR_GRACE = Duration.ofMinutes(2);
    static final int MAX_BYTES = 8 * 1024;
    private static final Pattern B64TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");
    private static final Logger log = LoggerFactory.getLogger(TokenProvider.class);

    private final Path file;
    private final LongSupplier nanoTime;
    private final long refreshNanos;
    private final Object lock = new Object();
    private volatile Snapshot snapshot;

    public TokenProvider(Path file) {
        this(file, System::nanoTime, REFRESH_INTERVAL);
    }

    TokenProvider(Path file, LongSupplier nanoTime, Duration refreshInterval) {
        this.file = Objects.requireNonNull(file, "file");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.refreshNanos = Objects.requireNonNull(refreshInterval, "refreshInterval").toNanos();
        try {
            long now = nanoTime.getAsLong();
            this.snapshot = new Snapshot(read(), now, now);
        } catch (IOException e) {
            // Fail fast at startup; the message names the file and the problem, never the content.
            throw new IllegalStateException("cannot read operator token from " + file + ": " + e.getMessage(), e);
        }
    }

    /** The current token; never null or blank. */
    public String token() {
        Snapshot current = snapshot;
        long now = nanoTime.getAsLong();
        if (now - current.checkedAt < refreshNanos) {
            return current.token;
        }
        synchronized (lock) {
            current = snapshot;
            if (now - current.checkedAt < refreshNanos) {
                return current.token;
            }
            try {
                snapshot = new Snapshot(read(), now, now);
            } catch (IOException e) {
                Duration stale = Duration.ofNanos(now - current.readAt);
                if (stale.compareTo(OPERATOR_GRACE) > 0) {
                    log.error("Cannot re-read operator token from {} ({}) for {}s; the operator likely rejects the kept token",
                            file, e.getMessage(), stale.toSeconds());
                } else {
                    log.warn("Cannot re-read operator token from {} ({}); keeping the previous token", file, e.getMessage());
                }
                snapshot = new Snapshot(current.token, current.readAt, now);
            }
            return snapshot.token;
        }
    }

    private String read() throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("token file is larger than " + MAX_BYTES + " bytes");
        }
        String value = new String(bytes, StandardCharsets.US_ASCII).strip();
        if (value.isEmpty()) {
            throw new IOException("token file is empty");
        }
        if (!B64TOKEN.matcher(value).matches()) {
            throw new IOException("token contains characters not allowed in a bearer token");
        }
        return value;
    }

    @Override
    public String toString() {
        return "TokenProvider[file=" + file + ", token=<redacted>]";
    }

    /** Holder without a revealing toString (a record would print the token). */
    private static final class Snapshot {
        private final String token;
        /** When {@link #token} was last read successfully. */
        private final long readAt;
        /** When the file was last tried. */
        private final long checkedAt;

        private Snapshot(String token, long readAt, long checkedAt) {
            this.token = token;
            this.readAt = readAt;
            this.checkedAt = checkedAt;
        }

        @Override
        public String toString() {
            return "Snapshot[token=<redacted>]";
        }
    }
}
