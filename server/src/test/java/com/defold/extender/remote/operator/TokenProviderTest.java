package com.defold.extender.remote.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TokenProviderTest {

    private static final Duration REFRESH = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    /** Monotonic clock the test moves by hand; starts at an arbitrary (even negative) value. */
    private static final class ManualNanos implements LongSupplier {
        private long now = -42_000_000_000L;

        void advance(Duration d) {
            now += d.toNanos();
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }

    private final ManualNanos clock = new ManualNanos();

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

    @Test
    void readsAndStrips() throws IOException {
        TokenProvider p = new TokenProvider(write("token", "  abc.DEF-123_~+/==\n"), clock, REFRESH);
        assertThat(p.token()).isEqualTo("abc.DEF-123_~+/==");
    }

    @Test
    void publicConstructorUsesDefaults() throws IOException {
        assertThat(new TokenProvider(write("token", "abc")).token()).isEqualTo("abc");
        assertThat(TokenProvider.REFRESH_INTERVAL).isEqualTo(REFRESH);
    }

    @Test
    void cachesWithinRefreshIntervalThenRereads() throws IOException {
        Path f = write("token", "old");
        TokenProvider p = new TokenProvider(f, clock, REFRESH);
        Files.writeString(f, "new");
        clock.advance(REFRESH.minusNanos(1));
        assertThat(p.token()).isEqualTo("old");
        clock.advance(Duration.ofNanos(1));
        assertThat(p.token()).isEqualTo("new");
    }

    @Test
    void followsKubernetesSymlinkRotation() throws IOException {
        // Kubelet layout: token -> ..data/token, ..data -> ..<timestamp>; rotation swaps ..data.
        Path v1 = Files.createDirectory(dir.resolve("..2026_11_24_v1"));
        Path v2 = Files.createDirectory(dir.resolve("..2026_11_24_v2"));
        Files.writeString(v1.resolve("token"), "first");
        Files.writeString(v2.resolve("token"), "second");
        Path data = Files.createSymbolicLink(dir.resolve("..data"), v1.getFileName());
        Path token = Files.createSymbolicLink(dir.resolve("token"), Path.of("..data", "token"));
        TokenProvider p = new TokenProvider(token, clock, REFRESH);
        assertThat(p.token()).isEqualTo("first");

        Path tmp = Files.createSymbolicLink(dir.resolve("..data_tmp"), v2.getFileName());
        Files.move(tmp, data, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        clock.advance(REFRESH);
        assertThat(p.token()).isEqualTo("second");
    }

    @Test
    void keepsLastGoodTokenWhenRereadFails() throws IOException {
        Path f = write("token", "good");
        TokenProvider p = new TokenProvider(f, clock, REFRESH);
        Files.delete(f);
        clock.advance(REFRESH);
        assertThat(p.token()).isEqualTo("good");
        Files.writeString(f, "bad token with spaces");
        clock.advance(REFRESH);
        assertThat(p.token()).isEqualTo("good");
        Files.writeString(f, "recovered");
        clock.advance(REFRESH);
        assertThat(p.token()).isEqualTo("recovered");
    }

    @Test
    void keepsTokenPastOperatorGraceAndRecovers() throws IOException {
        // Past the grace period the failure is logged as ERROR; the token is still kept (fail-open).
        Path f = write("token", "good");
        TokenProvider p = new TokenProvider(f, clock, REFRESH);
        Files.delete(f);
        for (Duration d = Duration.ZERO; d.compareTo(TokenProvider.OPERATOR_GRACE.plus(REFRESH)) <= 0; d = d.plus(REFRESH)) {
            clock.advance(REFRESH);
            assertThat(p.token()).isEqualTo("good");
        }
        Files.writeString(f, "fresh");
        clock.advance(REFRESH);
        assertThat(p.token()).isEqualTo("fresh");
    }

    @Test
    void failedRereadIsNotRetriedOnEveryCall() throws IOException {
        Path f = write("token", "good");
        TokenProvider p = new TokenProvider(f, clock, REFRESH);
        Files.delete(f);
        clock.advance(REFRESH);
        assertThat(p.token()).isEqualTo("good");
        Files.writeString(f, "newer");
        clock.advance(REFRESH.minusNanos(1));
        assertThat(p.token()).isEqualTo("good"); // backs off a full interval after a failure
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   \n", "two words", "line\r\nX-Injected: 1", "tab\there", "ünïcode", "a=b"})
    void rejectsInvalidTokensAtStartup(String content) throws IOException {
        Path f = write("token", content);
        assertThatThrownBy(() -> new TokenProvider(f, clock, REFRESH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot read operator token from " + f);
    }

    @Test
    void sizeCapped() throws IOException {
        Path ok = write("ok", "a".repeat(TokenProvider.MAX_BYTES));
        assertThat(new TokenProvider(ok, clock, REFRESH).token()).hasSize(TokenProvider.MAX_BYTES);
        Path big = write("big", "a".repeat(TokenProvider.MAX_BYTES + 1));
        assertThatThrownBy(() -> new TokenProvider(big, clock, REFRESH))
                .hasMessageContaining("larger than " + TokenProvider.MAX_BYTES + " bytes");
    }

    @Test
    void missingFileFailsFast() {
        Path f = dir.resolve("missing");
        assertThatThrownBy(() -> new TokenProvider(f, clock, REFRESH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(f.toString());
    }

    @Test
    void toStringIsRedacted() throws IOException {
        TokenProvider p = new TokenProvider(write("token", "topsecret"), clock, REFRESH);
        assertThat(p.toString()).doesNotContain("topsecret").contains("<redacted>");
    }
}
