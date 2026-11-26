package com.defold.extender.remote.operator;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.defold.extender.remote.RemoteInstanceController;

import com.defold.extender.remote.operator.TouchApiResponse.Failure;
import com.defold.extender.remote.operator.TouchApiResponse.Kind;
import com.defold.extender.remote.operator.TouchApiResponse.Ok;

/**
 * {@link RemoteInstanceController} backed by an extender operator's touch API (Kubernetes builders scaled from zero).
 *
 * <p>Controlled instances are the router's remote builders that are not {@code alwaysOn}; their
 * {@code instanceId} is the ExtenderBuilder name. {@link #touchInstance} is called before every
 * request a build sends; it blocks until the builder is Ready or {@code wait-timeout} passes
 * ({@link TimeoutException}, like the GCP controller). Upstream RemoteEngineBuilder logs any
 * exception and sends the request anyway, so waiting only decides how long a build is delayed.
 *
 * <p>Waiting polls with touch, not state: touch is the heartbeat the scaler's idle clock runs on, so
 * a slow wake can't be scaled away mid-start.
 *
 * <p>Fail-open: if the operator can't answer (unreachable, 5xx, 429, 401) and the builder was seen
 * warm within {@link #FAIL_OPEN_MAX_AGE}, the build proceeds with a WARN. Older knowledge doesn't
 * count — the builder may have idled to zero since — so the build waits like a cold one.
 */
public final class OperatorInstanceController implements RemoteInstanceController {

    /** Repeated failures are logged at most this often per builder (401 once for all builders). */
    static final Duration LOG_INTERVAL = Duration.ofMinutes(1);
    /** Trust a "warm" observation this long; below the operator's default idleTTL (15m). */
    static final Duration FAIL_OPEN_MAX_AGE = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(OperatorInstanceController.class);
    private static final String UNAUTHORIZED_LOG_KEY = "\u0000unauthorized";

    /** Sleeps for the controller; replaceable in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Timing knobs ({@code extender.operator.controller.*}). */
    public record Timing(Duration waitTimeout, Duration pollInterval, Duration stateCacheTtl) {
        public Timing {
            Objects.requireNonNull(waitTimeout, "waitTimeout");
            Objects.requireNonNull(pollInterval, "pollInterval");
            Objects.requireNonNull(stateCacheTtl, "stateCacheTtl");
        }
    }

    /**
     * What we know about one builder. {@code seenAt} = start of the request that returned
     * {@code state} (null until one succeeds); {@code attemptAt} = start of the latest request,
     * successful or not, so failures are cached too.
     */
    private record Entry(BuilderState state, long seenAt, long attemptAt) {
    }

    private final TouchApiClient client;
    private final Set<String> controlled;
    private final Timing timing;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();

    public OperatorInstanceController(TouchApiClient client, Set<String> controlledInstanceIds, Timing timing) {
        this(client, controlledInstanceIds, timing, System::nanoTime, d -> Thread.sleep(d));
    }

    OperatorInstanceController(TouchApiClient client, Set<String> controlledInstanceIds, Timing timing,
            LongSupplier nanoTime, Sleeper sleeper) {
        this.client = Objects.requireNonNull(client, "client");
        this.controlled = Set.copyOf(controlledInstanceIds);
        this.timing = Objects.requireNonNull(timing, "timing");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        for (String id : controlled) {
            if (!TouchApiClient.validName(id)) {
                throw new IllegalArgumentException("instance id '" + id + "' is not a DNS-1123 label (ExtenderBuilder name)");
            }
        }
    }

    @Override
    public boolean isInstanceControlled(String instanceId) {
        // Set.copyOf().contains(null) throws; the health report asks for platforms without an id too.
        return instanceId != null && controlled.contains(instanceId);
    }

    @Override
    public void touchInstance(String instanceId) throws InterruptedException, TimeoutException {
        if (!isInstanceControlled(instanceId)) {
            return;
        }
        long start = nanoTime.getAsLong();
        long deadline = start + timing.waitTimeout().toNanos();
        boolean warm = recentlyWarm(instanceId, start);
        while (true) {
            long requestStart = nanoTime.getAsLong();
            TouchApiResponse response = client.touch(instanceId);
            Duration wait = timing.pollInterval();
            if (response instanceof Ok ok) {
                remember(instanceId, ok.state(), requestStart);
                if (ok.state().warm()) {
                    return;
                }
            } else if (response instanceof Failure failure) {
                attempted(instanceId, requestStart);
                if (!failure.kind().transientFailure()) {
                    // 404/other rejection: retrying can't help, and upstream would log an exception
                    // with a stack trace on every touch (every few seconds per build) and proceed anyway.
                    logRejection(instanceId, failure);
                    return;
                }
                logFailure(instanceId, failure, warm);
                if (warm) {
                    return; // fail-open: the builder was serving moments ago
                }
                wait = failure.retryAfter().filter(d -> d.compareTo(timing.pollInterval()) > 0).orElse(wait);
            }
            long now = nanoTime.getAsLong();
            if (now - deadline >= 0) {
                throw new TimeoutException("builder '" + instanceId + "' not ready after "
                        + Duration.ofNanos(now - start).toSeconds() + "s (" + lastSeen(instanceId, response) + ")");
            }
            Duration remaining = Duration.ofNanos(deadline - now);
            sleeper.sleep(wait.compareTo(remaining) < 0 ? wait : remaining);
        }
    }

    @Override
    public boolean isInstanceRunning(String instanceId) {
        return phase(instanceId) == Phase.READY;
    }

    @Override
    public boolean isInstanceSuspended(String instanceId) {
        return phase(instanceId) == Phase.COLD;
    }

    @Override
    public boolean isInstanceSuspending(String instanceId) {
        return phase(instanceId) == Phase.DRAINING;
    }

    @Override
    public boolean isInstanceStarting(String instanceId) {
        return phase(instanceId) == Phase.WAKING;
    }

    /**
     * Phase for the health report, which asks up to four questions per builder in a row: at most one
     * request per state-cache-ttl, successful or not. If the operator can't answer, the last phase seen
     * within {@link #FAIL_OPEN_MAX_AGE}; otherwise null (unknown → every isInstance* is false).
     */
    private Phase phase(String instanceId) {
        if (!isInstanceControlled(instanceId)) {
            return null;
        }
        long now = nanoTime.getAsLong();
        long ttl = timing.stateCacheTtl().toNanos();
        Entry entry = entries.get(instanceId);
        if (entry != null && now - entry.attemptAt() < ttl) {
            return usable(entry, now, entry.state() != null && now - entry.seenAt() < ttl ? ttl : FAIL_OPEN_MAX_AGE.toNanos());
        }
        TouchApiResponse response = client.state(instanceId);
        if (response instanceof Ok ok) {
            remember(instanceId, ok.state(), now);
            return effectivePhase(ok.state());
        }
        attempted(instanceId, now);
        Failure failure = (Failure) response;
        logFailure(instanceId, failure, recentlyWarm(instanceId, now));
        return usable(entries.get(instanceId), now, FAIL_OPEN_MAX_AGE.toNanos());
    }

    private static Phase usable(Entry entry, long now, long maxAgeNanos) {
        if (entry == null || entry.state() == null || now - entry.seenAt() >= maxAgeNanos) {
            return null;
        }
        return effectivePhase(entry.state());
    }

    private static Phase effectivePhase(BuilderState state) {
        return state.alwaysOn() ? Phase.READY : state.phase();
    }

    private boolean recentlyWarm(String instanceId, long now) {
        Entry entry = entries.get(instanceId);
        return entry != null && entry.state() != null && entry.state().warm()
                && now - entry.seenAt() < FAIL_OPEN_MAX_AGE.toNanos();
    }

    /** Records a successful answer unless a newer request already did (last request wins, not last reply). */
    private void remember(String instanceId, BuilderState state, long requestStart) {
        entries.merge(instanceId, new Entry(state, requestStart, requestStart), (old, fresh) -> {
            boolean newer = old.state() == null || fresh.seenAt() - old.seenAt() >= 0;
            BuilderState keep = newer ? fresh.state() : old.state();
            long seenAt = newer ? fresh.seenAt() : old.seenAt();
            return new Entry(keep, seenAt, Math.max(old.attemptAt(), fresh.attemptAt()));
        });
    }

    private void attempted(String instanceId, long requestStart) {
        entries.merge(instanceId, new Entry(null, 0, requestStart),
                (old, fresh) -> new Entry(old.state(), old.seenAt(), Math.max(old.attemptAt(), requestStart)));
    }

    private String lastSeen(String instanceId, TouchApiResponse response) {
        if (response instanceof Failure failure) {
            return "operator: " + describe(failure);
        }
        return "last phase " + Optional.ofNullable(entries.get(instanceId)).map(Entry::state)
                .map(s -> s.phase().name()).orElse("unknown");
    }

    private static String describe(Failure failure) {
        return failure.status() == 0 ? failure.detail() : failure.status() + " " + failure.detail();
    }

    private void logRejection(String instanceId, Failure failure) {
        if (shouldLog(instanceId + "\u0000" + failure.kind())) {
            log.error("Extender operator refused touch for builder '{}' ({}); not waiting for it."
                    + " Check that an ExtenderBuilder with this name exists in the router's namespace.",
                    instanceId, describe(failure));
        }
    }

    private void logFailure(String instanceId, Failure failure, boolean warm) {
        if (failure.kind() == Kind.UNAUTHORIZED) {
            // Never the token itself; C1: a fresh token may be rejected until the operator re-reads it.
            if (shouldLog(UNAUTHORIZED_LOG_KEY)) {
                log.error("Extender operator rejected the router's bearer token (401); check the token Secret and"
                        + " its rotation. Treating the operator as unreachable.");
            }
            return;
        }
        if (shouldLog(instanceId)) {
            log.warn("Extender operator can't answer for builder '{}' ({}); {}", instanceId, describe(failure),
                    warm ? "it was warm, proceeding (fail-open)" : "waiting for it");
        }
    }

    /** True at most once per {@link #LOG_INTERVAL} per key. */
    boolean shouldLog(String key) {
        long now = nanoTime.getAsLong();
        long interval = LOG_INTERVAL.toNanos();
        Long previous = lastLogged.get(key);
        if (previous != null && now - previous < interval) {
            return false;
        }
        // Only the thread that installs the new timestamp logs.
        return previous == null ? lastLogged.putIfAbsent(key, now) == null : lastLogged.replace(key, previous, now);
    }

    @Override
    public String toString() {
        return "OperatorInstanceController[" + client + ", controlled=" + controlled + "]";
    }
}
