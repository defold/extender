package com.defold.extender.remote.operator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.defold.extender.remote.RemoteHostConfiguration;
import com.defold.extender.remote.RemoteInstanceConfig;
import com.defold.extender.remote.RemoteInstanceController;


class OperatorControllerConfigurationTest {

    private static final String SECRET = "s3cr3t-token-value"; // gitleaks:allow (test fixture)

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OperatorControllerConfiguration.class)
            .withBean(RemoteHostConfiguration.class, OperatorControllerConfigurationTest::remoteHosts);

    /** Like the router's extender.remote-builder.platforms: two scalable builders, one external macOS. */
    private static RemoteHostConfiguration remoteHosts() {
        RemoteHostConfiguration hosts = new RemoteHostConfiguration();
        hosts.getPlatforms().put("x86_64-linux-latest", new RemoteInstanceConfig("http://b1", "linux-latest", false));
        hosts.getPlatforms().put("armv7-android-ndk25-sdk36", new RemoteInstanceConfig("http://b2", "android-ndk25-sdk36", false));
        hosts.getPlatforms().put("arm64-ios-latest", new RemoteInstanceConfig("http://mac", "mac-external", true));
        hosts.getPlatforms().put("js-web-latest", new RemoteInstanceConfig("http://b3", null, false));
        // Seen in upstream's local-dev-app profile: a GCE-style id, not an ExtenderBuilder name.
        hosts.getPlatforms().put("x86_64-win32-2026", new RemoteInstanceConfig("http://win", "winsdk-2026_145136231", false));
        return hosts;
    }

    @Test
    void providesTheRoutersRemoteInstanceController() {
        runner.withPropertyValues(enabled()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(RemoteInstanceController.class);
            assertThat(ctx).hasSingleBean(TouchApiClient.class);
            RemoteInstanceController c = ctx.getBean(RemoteInstanceController.class);
            assertThat(c).isInstanceOf(OperatorInstanceController.class);
            assertThat(c.isInstanceControlled("linux-latest")).isTrue();
            assertThat(c.isInstanceControlled("android-ndk25-sdk36")).isTrue();
            assertThat(c.isInstanceControlled("mac-external")).isFalse(); // alwaysOn, like the GCP controller
        });
    }

    @Test
    void controlledIdsSkipAlwaysOnAndMissingIds() {
        assertThat(OperatorControllerConfiguration.controlledInstanceIds(remoteHosts().getPlatforms()))
                .containsExactly("android-ndk25-sdk36", "linux-latest");
        assertThat(OperatorControllerConfiguration.controlledInstanceIds(null)).isEmpty();
    }

    @Test
    void mutuallyExclusiveWithGcpController() {
        runner.withPropertyValues(enabled("extender.gcp.controller.enabled=true")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("mutually exclusive");
        });
    }

    @Test
    void noControllerWhenDisabled() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RemoteInstanceController.class));
    }

    @TempDir
    Path dir;
    private Path tokenFile;

    @BeforeEach
    void writeToken() throws IOException {
        tokenFile = Files.writeString(dir.resolve("token"), SECRET + "\n");
    }

    private String[] enabled(String... extra) {
        List<String> props = new ArrayList<>(List.of(
                "extender.operator.controller.enabled=true",
                "extender.operator.controller.url=http://extender-operator.defold-extender.svc:8090",
                "extender.operator.controller.token-file=" + tokenFile));
        props.addAll(List.of(extra));
        return props.toArray(String[]::new);
    }

    @Test
    void absentWithoutProperty() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(TokenProvider.class);
            assertThat(ctx).doesNotHaveBean(OperatorControllerProperties.class);
        });
    }

    @Test
    void absentWhenDisabledEvenIfOtherwiseInvalid() {
        // Disabled must not bind/validate anything: a router without the token mount still starts.
        runner.withPropertyValues("extender.operator.controller.enabled=false",
                        "extender.operator.controller.url=ftp://nope")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(TokenProvider.class);
                });
    }

    @Test
    void presentWithDefaultsWhenEnabled() {
        runner.withPropertyValues(enabled()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(TokenProvider.class);
            assertThat(ctx.getBean(TokenProvider.class).token()).isEqualTo(SECRET);
            OperatorControllerProperties p = ctx.getBean(OperatorControllerProperties.class);
            assertThat(p.waitTimeout()).isEqualTo(Duration.ofMinutes(15));
            assertThat(p.waitTimeouts()).isEmpty();
            assertThat(p.pollInterval()).isEqualTo(Duration.ofSeconds(2));
            assertThat(p.stateCacheTtl()).isEqualTo(Duration.ofSeconds(10));
            assertThat(p.url().getHost()).isEqualTo("extender-operator.defold-extender.svc");
        });
    }

    @Test
    void bindsOverrides() {
        runner.withPropertyValues(enabled(
                        "extender.operator.controller.url=https://op.example:9443/",
                        "extender.operator.controller.wait-timeout=90s",
                        "extender.operator.controller.poll-interval=500ms",
                        "extender.operator.controller.state-cache-ttl=1m"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    OperatorControllerProperties p = ctx.getBean(OperatorControllerProperties.class);
                    assertThat(p.waitTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(p.pollInterval()).isEqualTo(Duration.ofMillis(500));
                    assertThat(p.stateCacheTtl()).isEqualTo(Duration.ofMinutes(1));
                });
    }


    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "HTTP://Op.Svc:8090/, http://Op.Svc:8090",
            "https://op.example, https://op.example",
            "http://[::1]:8090, http://[::1]:8090",
            "http://10.0.0.7:8090/, http://10.0.0.7:8090"})
    void normalizesUrl(String in, String want) {
        runner.withPropertyValues(enabled("extender.operator.controller.url=" + in)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(OperatorControllerProperties.class).url()).hasToString(want);
        });
    }

    @Test
    void unitlessDurationsAreSeconds() {
        runner.withPropertyValues(enabled("extender.operator.controller.state-cache-ttl=10")).run(ctx ->
                assertThat(ctx.getBean(OperatorControllerProperties.class).stateCacheTtl()).isEqualTo(Duration.ofSeconds(10)));
    }

    @Test
    void bindsPerBuilderWaitTimeouts() {
        // As rendered by the operator: keyed by instance id (= ExtenderBuilder name, dashes included).
        runner.withPropertyValues(enabled(
                        "extender.operator.controller.wait-timeouts.android-ndk25-sdk36=1500000ms",
                        "extender.operator.controller.wait-timeouts.linux-latest=20m"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    OperatorControllerProperties p = ctx.getBean(OperatorControllerProperties.class);
                    assertThat(p.waitTimeoutFor("android-ndk25-sdk36")).isEqualTo(Duration.ofMinutes(25));
                    assertThat(p.waitTimeoutFor("linux-latest")).isEqualTo(Duration.ofMinutes(20));
                    assertThat(p.waitTimeoutFor("other")).isEqualTo(Duration.ofMinutes(15));
                });
    }

    @Test
    void unitlessPerBuilderWaitTimeoutsAreSeconds() {
        // Same rule as the scalar durations; Spring's default for a bare number would be milliseconds.
        runner.withPropertyValues(enabled(
                        "extender.operator.controller.wait-timeouts.linux-latest=90",
                        "extender.operator.controller.wait-timeouts.android-ndk25-sdk36=3000"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    OperatorControllerProperties p = ctx.getBean(OperatorControllerProperties.class);
                    assertThat(p.waitTimeoutFor("linux-latest")).isEqualTo(Duration.ofSeconds(90));
                    assertThat(p.waitTimeoutFor("android-ndk25-sdk36")).isEqualTo(Duration.ofSeconds(3000));
                });
    }

    @Test
    void userBeanWins() {
        TokenProvider custom = new TokenProvider(tokenFile);
        runner.withPropertyValues(enabled())
                .withBean(TokenProvider.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(TokenProvider.class)).isSameAs(custom));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("extender.operator.controller.url=", "url is required"),
                Arguments.of("extender.operator.controller.url=ftp://op:21", "scheme must be http or https"),
                Arguments.of("extender.operator.controller.url=op.svc:8090", "scheme must be http or https"),
                Arguments.of("extender.operator.controller.url=http:///path", "must have a host"),
                Arguments.of("extender.operator.controller.url=http://u:p@op:8090", "must not contain user info"),
                Arguments.of("extender.operator.controller.url=http://op:8090?x=1", "must not contain a query"),
                Arguments.of("extender.operator.controller.url=http://op:8090/v1", "without a path"),
                Arguments.of("extender.operator.controller.url=http://op:0", "port must be 1-65535"),
                Arguments.of("extender.operator.controller.url=http://op:99999", "port must be 1-65535"),
                Arguments.of("extender.operator.controller.url=http://bad_host:8090", "must have a host"),
                Arguments.of("extender.operator.controller.wait-timeout=0s", "wait-timeout must be a positive"),
                Arguments.of("extender.operator.controller.wait-timeouts.linux-latest=0s", "wait-timeouts.linux-latest must be a positive"),
                Arguments.of("extender.operator.controller.wait-timeouts.linux-latest=1s", "wait-timeouts.linux-latest must be longer than poll-interval"),
                Arguments.of("extender.operator.controller.wait-timeouts.linux-latest=soon", "wait-timeouts.linux-latest is not a duration"),
                Arguments.of("extender.operator.controller.poll-interval=-1s", "poll-interval must be a positive"),
                Arguments.of("extender.operator.controller.state-cache-ttl=0ms", "state-cache-ttl must be a positive"),
                Arguments.of("extender.operator.controller.poll-interval=20m", "must be shorter than wait-timeout"),
                Arguments.of("extender.operator.controller.token-file=/does/not/exist", "is not a readable file"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void failsStartupOnInvalidConfig(String override, String expected) {
        runner.withPropertyValues(enabled(override)).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining(expected);
        });
    }

    @Test
    void tokenFileDirectoryRejected() {
        runner.withPropertyValues(enabled("extender.operator.controller.token-file=" + dir)).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("is not a readable file");
        });
    }

    @Test
    void emptyTokenFailsStartupWithoutLeakingAnything() throws IOException {
        Files.writeString(tokenFile, "  \n");
        runner.withPropertyValues(enabled()).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("token file is empty");
        });
    }

    @Test
    void startupFailureNeverContainsToken() throws IOException {
        Files.writeString(tokenFile, SECRET + " injected\r\nX-Evil: 1");
        runner.withPropertyValues(enabled()).run(ctx -> {
            assertThat(ctx).hasFailed();
            Throwable t = ctx.getStartupFailure();
            while (t != null) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(SECRET);
                t = t.getCause();
            }
        });
    }
}
