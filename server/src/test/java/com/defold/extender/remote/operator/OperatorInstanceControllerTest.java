package com.defold.extender.remote.operator;

import static com.defold.extender.remote.operator.StubOperator.error;
import static com.defold.extender.remote.operator.StubOperator.ready;
import static com.defold.extender.remote.operator.StubOperator.state;
import static com.defold.extender.remote.operator.StubOperator.waking;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.defold.extender.remote.operator.StubOperator.Seen;

class OperatorInstanceControllerTest {

    private static final String TOKEN = "e2e-token-0123456789abcdef"; // gitleaks:allow (test fixture)
    private static final String BUILDER = "linux-latest";
    private static final OperatorInstanceController.Timing TIMING = new OperatorInstanceController.Timing(
            Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(10));

    @TempDir
    Path dir;
    private StubOperator operator;
    private TokenProvider tokens;
    private final List<TouchApiClient> clients = new ArrayList<>();

    /** Fake monotonic time; the controller's "sleep" just moves it forward and is recorded. */
    private long now = 1_000_000_000L;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        operator = new StubOperator();
        tokens = new TokenProvider(Files.writeString(dir.resolve("token"), TOKEN));
    }

    @AfterEach
    void tearDown() throws IOException {
        operator.close();
        for (TouchApiClient c : clients) {
            c.close();
        }
    }

    private OperatorInstanceController controller(URI base, OperatorInstanceController.Timing timing) {
        TouchApiClient client = new TouchApiClient(base, tokens, Duration.ofMillis(500), Duration.ofSeconds(2));
        clients.add(client);
        return new OperatorInstanceController(client, Set.of(BUILDER, "android"), timing, () -> now, d -> {
            sleeps.add(d);
            now += d.toNanos();
        });
    }

    private OperatorInstanceController controller() {
        return controller(operator.uri(), TIMING);
    }

    private void advance(Duration d) {
        now += d.toNanos();
    }

    private static URI closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + s.getLocalPort());
        }
    }

    // --- touch ---

    @Test
    void readyReturnsAfterOneTouchWithBearerToken() throws Exception {
        operator.onTouch(ready());
        controller().touchInstance(BUILDER);
        assertThat(operator.seen()).containsExactly(new Seen("POST", "/v1/builders/linux-latest/touch", "Bearer " + TOKEN));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void wakingKeepsTouchingUntilReady() throws Exception {
        // Touch, not GET state: touches are the heartbeat that keeps the scaler from idling a slow wake away.
        operator.onTouch(waking(), waking(), ready());
        controller().touchInstance(BUILDER);
        assertThat(operator.seen()).extracting(Seen::method).containsExactly("POST", "POST", "POST");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Test
    void wakingTimesOutWithTimeoutException() {
        operator.onTouch(waking());
        OperatorInstanceController c = controller();
        assertThatThrownBy(() -> c.touchInstance(BUILDER))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("builder 'linux-latest' not ready after 300s")
                .hasMessageContaining("last phase WAKING")
                .hasMessageNotContaining(TOKEN);
        // Last sleep is clipped to the deadline, never past it.
        assertThat(sleeps.stream().reduce(Duration.ZERO, Duration::plus)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void perBuilderWaitTimeoutOverridesTheDefault() {
        operator.onTouch(waking());
        OperatorInstanceController.Timing timing = new OperatorInstanceController.Timing(
                Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(10), Map.of(BUILDER, Duration.ofMinutes(25)));
        OperatorInstanceController c = controller(operator.uri(), timing);
        assertThatThrownBy(() -> c.touchInstance(BUILDER))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("not ready after 1500s");
        sleeps.clear();
        assertThatThrownBy(() -> c.touchInstance("android"))   // no override: the default 5m
                .hasMessageContaining("not ready after 300s");
    }

    @Test
    void anyNonWarmPhaseKeepsTouching() throws Exception {
        // The server answers touches with Ready/Waking only, but don't rely on it.
        operator.onTouch(state("Cold", false), state("Draining", false), ready());
        controller().touchInstance(BUILDER);
        assertThat(operator.count("POST")).isEqualTo(3);
        assertThat(operator.count("GET")).isZero();
    }

    @Test
    void alwaysOnStateIsWarm() throws Exception {
        operator.onTouch(state("Cold", true));
        controller().touchInstance(BUILDER);
        assertThat(operator.count("POST")).isEqualTo(1);
    }

    @Test
    void uncontrolledInstancesAreNotTouched() throws Exception {
        OperatorInstanceController c = controller();
        assertThat(c.isInstanceControlled("mac-external")).isFalse();
        c.touchInstance("mac-external");
        c.touchInstance(null);
        // HealthReporterService asks about every platform, including ones without an instanceId.
        assertThat(c.isInstanceControlled(null)).isFalse();
        assertThat(c.isInstanceRunning(null)).isFalse();
        assertThat(c.isInstanceRunning("mac-external")).isFalse();
        assertThat(operator.seen()).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"404, unknown_builder", "400, invalid_name", "405, method_not_allowed", "413, body_too_large"})
    void rejectionsReturnWithoutWaitingOrThrowing(int status, String code) throws Exception {
        // Upstream would log a stack trace per touch and proceed anyway; we log (rate-limited) and return.
        operator.onTouch(error(status, code));
        OperatorInstanceController c = controller();
        c.touchInstance(BUILDER);
        c.touchInstance(BUILDER);
        assertThat(operator.count("POST")).isEqualTo(2);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void malformedBodiesAreRejections() throws Exception {
        operator.onTouch(new StubOperator.Reply(200, "{\"phase\":\"Sideways\"}", Map.of()));
        OperatorInstanceController c = controller();
        c.touchInstance(BUILDER);
        operator.onTouch(new StubOperator.Reply(200, "not json", Map.of()));
        c.touchInstance(BUILDER);
        assertThat(sleeps).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"503, not_leader", "503, store_timeout", "500, internal"})
    void transientServerErrorsAreRetriedForColdBuilders(int status, String code) throws Exception {
        operator.onTouch(error(status, code), error(status, code), ready());
        controller().touchInstance(BUILDER);
        assertThat(operator.count("POST")).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Test
    void rateLimitHonorsRetryAfter() throws Exception {
        operator.onTouch(error(429, "rate_limited", Map.of("Retry-After", "7")), ready());
        controller().touchInstance(BUILDER);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void retryAfterIsCappedAndNeverShortensThePollInterval() throws Exception {
        operator.onTouch(error(429, "rate_limited", Map.of("Retry-After", "3600")),
                error(503, "not_leader", Map.of("Retry-After", "0")),
                error(503, "not_leader", Map.of("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")), ready());
        controller().touchInstance(BUILDER);
        assertThat(sleeps).containsExactly(TouchApiClient.MAX_RETRY_AFTER, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Test
    void sleepNeverPassesTheDeadline() {
        OperatorInstanceController.Timing tight = new OperatorInstanceController.Timing(
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(10));
        operator.onTouch(error(429, "rate_limited", Map.of("Retry-After", "30")));
        OperatorInstanceController c = controller(operator.uri(), tight);
        assertThatThrownBy(() -> c.touchInstance(BUILDER))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("operator: 429 rate_limited");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(5));
    }

    @Test
    void interruptPropagates() {
        operator.onTouch(waking());
        TouchApiClient client = new TouchApiClient(operator.uri(), tokens);
        clients.add(client);
        OperatorInstanceController c = new OperatorInstanceController(client, Set.of(BUILDER), TIMING, () -> now, d -> {
            throw new InterruptedException("stop");
        });
        assertThatThrownBy(() -> c.touchInstance(BUILDER)).isInstanceOf(InterruptedException.class);
    }

    // --- fail-open ---

    @Test
    void operatorDownRecentlyWarmBuilderProceeds() throws Exception {
        operator.onTouch(ready());
        OperatorInstanceController c = controller();
        c.touchInstance(BUILDER); // seen Ready now
        operator.close();
        advance(OperatorInstanceController.FAIL_OPEN_MAX_AGE.minusSeconds(1));
        c.touchInstance(BUILDER);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void staleWarmKnowledgeDoesNotFailOpen() throws Exception {
        // Ready long ago may have idled to zero since: wait like a cold builder (e.g. through a leader failover).
        operator.onTouch(ready());
        OperatorInstanceController c = controller();
        c.touchInstance(BUILDER);
        advance(OperatorInstanceController.FAIL_OPEN_MAX_AGE);
        operator.onTouch(error(503, "not_leader"), error(503, "not_leader"), waking(), ready());
        c.touchInstance(BUILDER);
        assertThat(sleeps).hasSize(3);
    }

    @Test
    void operatorDownColdBuilderTimesOut() throws IOException {
        OperatorInstanceController c = controller(closedPort(), TIMING);
        assertThatThrownBy(() -> c.touchInstance(BUILDER))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("operator: HttpHostConnectException")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void alwaysOnFromStateIsWarm() throws Exception {
        operator.onState(state("Cold", true));
        OperatorInstanceController c = controller();
        assertThat(c.isInstanceRunning(BUILDER)).isTrue();
        operator.onTouch(error(503, "not_leader"));
        c.touchInstance(BUILDER);
        assertThat(operator.count("POST")).isEqualTo(1);
    }

    @Test
    void unauthorizedIsTreatedAsUnreachable() throws Exception {
        operator.onTouch(error(401, "unauthorized", Map.of("WWW-Authenticate", "Bearer")), ready());
        OperatorInstanceController c = controller();
        c.touchInstance(BUILDER); // cold: waits and retries (token may be mid-rotation)
        assertThat(operator.count("POST")).isEqualTo(2);
        assertThat(sleeps).hasSize(1);

        operator.onTouch(error(401, "unauthorized"));
        c.touchInstance(BUILDER); // now warm: fail-open, no wait
        assertThat(sleeps).hasSize(1);
    }

    // --- state / health report ---

    @ParameterizedTest(name = "{0} alwaysOn={1}")
    @CsvSource({
            "Ready, false, true, false, false, false",
            "Cold, false, false, true, false, false",
            "Waking, false, false, false, false, true",
            "Draining, false, false, false, true, false",
            "Cold, true, true, false, false, false"})
    void phaseMapping(String phase, boolean alwaysOn, boolean running, boolean suspended, boolean suspending,
            boolean starting) {
        operator.onState(state(phase, alwaysOn));
        OperatorInstanceController c = controller();
        assertThat(c.isInstanceRunning(BUILDER)).isEqualTo(running);
        assertThat(c.isInstanceSuspended(BUILDER)).isEqualTo(suspended);
        assertThat(c.isInstanceSuspending(BUILDER)).isEqualTo(suspending);
        assertThat(c.isInstanceStarting(BUILDER)).isEqualTo(starting);
    }

    @Test
    void stateIsCachedForTtl() {
        operator.onState(state("Cold", false));
        OperatorInstanceController c = controller();
        c.isInstanceSuspended(BUILDER);
        c.isInstanceSuspending(BUILDER);
        c.isInstanceStarting(BUILDER);
        c.isInstanceRunning(BUILDER);
        assertThat(operator.count("GET")).isEqualTo(1); // the health report asks up to 4 questions per builder
        advance(Duration.ofSeconds(10));
        c.isInstanceRunning(BUILDER);
        assertThat(operator.count("GET")).isEqualTo(2);
    }

    @Test
    void failuresAreCachedTooSoTheHealthReportCantStall() {
        operator.onState(error(503, "store_timeout"));
        OperatorInstanceController c = controller();
        assertThat(c.isInstanceSuspended(BUILDER)).isFalse();
        assertThat(c.isInstanceSuspending(BUILDER)).isFalse();
        assertThat(c.isInstanceStarting(BUILDER)).isFalse();
        assertThat(c.isInstanceRunning(BUILDER)).isFalse();
        assertThat(operator.count("GET")).isEqualTo(1);
        advance(Duration.ofSeconds(10));
        c.isInstanceRunning(BUILDER);
        assertThat(operator.count("GET")).isEqualTo(2);
    }

    @Test
    void touchResponseRefreshesTheCache() throws Exception {
        operator.onTouch(ready());
        OperatorInstanceController c = controller();
        c.touchInstance(BUILDER);
        assertThat(c.isInstanceRunning(BUILDER)).isTrue();
        assertThat(operator.count("GET")).isZero();
    }

    @Test
    void stateFailureFallsBackToRecentKnowledgeOnly() {
        operator.onState(state("Cold", false), error(503, "not_leader"));
        OperatorInstanceController c = controller();
        assertThat(c.isInstanceSuspended(BUILDER)).isTrue();
        advance(Duration.ofSeconds(11));
        assertThat(c.isInstanceSuspended(BUILDER)).isTrue(); // stale but recent: still known
        advance(OperatorInstanceController.FAIL_OPEN_MAX_AGE);
        assertThat(c.isInstanceSuspended(BUILDER)).isFalse(); // too old: unknown
        assertThat(c.isInstanceRunning("android")).isFalse(); // never known
    }

    @Test
    void closedClientIsUnreachableNotAnException() throws IOException {
        OperatorInstanceController c = controller();
        clients.get(0).close();
        assertThat(c.isInstanceRunning(BUILDER)).isFalse();
    }

    @Test
    void repeatedFailuresAreLoggedOncePerInterval() {
        OperatorInstanceController c = controller();
        assertThat(c.shouldLog("k")).isTrue();
        assertThat(c.shouldLog("k")).isFalse();
        assertThat(c.shouldLog("other")).isTrue();
        advance(OperatorInstanceController.LOG_INTERVAL);
        assertThat(c.shouldLog("k")).isTrue();
    }

    // --- transport safety ---

    @Test
    void redirectsAreNotFollowed() throws Exception {
        try (StubOperator elsewhere = new StubOperator()) {
            elsewhere.onTouch(ready());
            operator.onTouch(new StubOperator.Reply(307, "", Map.of("Location", elsewhere.uri() + "/v1/builders/linux-latest/touch")));
            controller().touchInstance(BUILDER); // a rejection: returns without waiting
            assertThat(elsewhere.seen()).isEmpty(); // the token never left for another host
            assertThat(sleeps).isEmpty();
        }
    }

    @Test
    void unknownErrorBodyIsNotEchoed() {
        operator.onTouch(new StubOperator.Reply(503, "{\"error\":\"<script>" + TOKEN + "\"}", Map.of()));
        OperatorInstanceController.Timing tight = new OperatorInstanceController.Timing(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10));
        OperatorInstanceController c = controller(operator.uri(), tight);
        assertThatThrownBy(() -> c.touchInstance(BUILDER))
                .hasMessageContaining("503 unexpected response")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void oversizedBodyIsNotBuffered() {
        operator.onTouch(new StubOperator.Reply(200, "{\"phase\":\"Ready\",\"pad\":\"" + "x".repeat(TouchApiClient.MAX_BODY_BYTES) + "\"}", Map.of()));
        OperatorInstanceController.Timing tight = new OperatorInstanceController.Timing(
                Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(10));
        OperatorInstanceController c = controller(operator.uri(), tight);
        assertThatThrownBy(() -> c.touchInstance(BUILDER)).isInstanceOf(TimeoutException.class)
                .hasMessageContaining("larger than " + TouchApiClient.MAX_BODY_BYTES);
    }

    @Test
    void invalidInstanceIdsAreRejectedUpFront() {
        TouchApiClient client = new TouchApiClient(operator.uri(), tokens);
        clients.add(client);
        assertThatThrownBy(() -> new OperatorInstanceController(client, Set.of("../admin"), TIMING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a DNS-1123 label");
        assertThatThrownBy(() -> client.touch("Bad_Name")).isInstanceOf(IllegalArgumentException.class);
        assertThat(client.toString()).doesNotContain(TOKEN);
        assertThat(new OperatorInstanceController(client, Set.of(BUILDER), TIMING).toString()).doesNotContain(TOKEN);
    }
}
