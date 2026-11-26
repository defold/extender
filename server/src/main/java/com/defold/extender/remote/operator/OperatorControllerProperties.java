package com.defold.extender.remote.operator;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

/**
 * {@code extender.operator.controller.*}. Only bound when the operator controller is enabled.
 *
 * <p>Validated in the constructor rather than with {@code @Validated}: the router ships no Bean
 * Validation provider, and without one Spring silently skips constraint annotations. A failure here
 * makes the router fail to start with the offending property in the message.
 *
 * <p>{@code url} is normalized to {@code scheme://authority} (lower-case scheme, no trailing slash) so
 * callers can append {@code /v1/...}. Plain http is allowed: the touch API is plain HTTP on 8443 per
 * the operator's design. Durations without a unit are seconds.
 */
@ConfigurationProperties(OperatorControllerProperties.PREFIX)
public record OperatorControllerProperties(
        boolean enabled,
        URI url,
        @DefaultValue(DEFAULT_TOKEN_FILE) Path tokenFile,
        @DefaultValue("5m") @DurationUnit(ChronoUnit.SECONDS) Duration waitTimeout,
        @DefaultValue("2s") @DurationUnit(ChronoUnit.SECONDS) Duration pollInterval,
        @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) Duration stateCacheTtl) {

    public static final String PREFIX = "extender.operator.controller";
    static final String DEFAULT_TOKEN_FILE = "/var/run/secrets/extender-operator/token";
    private static final Set<String> SCHEMES = Set.of("http", "https");

    public OperatorControllerProperties {
        validateUrl(url);
        url = URI.create(url.getScheme().toLowerCase(Locale.ROOT) + "://" + url.getRawAuthority());
        validateTokenFile(tokenFile);
        requirePositive("wait-timeout", waitTimeout);
        requirePositive("poll-interval", pollInterval);
        requirePositive("state-cache-ttl", stateCacheTtl);
        if (pollInterval.compareTo(waitTimeout) >= 0) {
            throw invalid("poll-interval", "must be shorter than wait-timeout (" + waitTimeout + ")");
        }
    }

    private static void validateUrl(URI url) {
        if (url == null) {
            throw invalid("url", "is required");
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(scheme)) {
            throw invalid("url", "scheme must be http or https, got '" + url.getScheme() + "'");
        }
        if (url.getHost() == null || url.getHost().isEmpty()) {
            throw invalid("url", "must have a host");
        }
        if (url.getPort() == 0 || url.getPort() > 65535) {
            throw invalid("url", "port must be 1-65535, got " + url.getPort());
        }
        // Credentials belong in token-file; a URL with userinfo would leak them into logs.
        if (url.getRawUserInfo() != null) {
            throw invalid("url", "must not contain user info");
        }
        if (url.getRawQuery() != null || url.getRawFragment() != null) {
            throw invalid("url", "must not contain a query or fragment");
        }
        String path = url.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw invalid("url", "must be a base URL without a path");
        }
    }

    private static void validateTokenFile(Path tokenFile) {
        if (tokenFile == null) {
            throw invalid("token-file", "is required");
        }
        if (!Files.isRegularFile(tokenFile) || !Files.isReadable(tokenFile)) {
            throw invalid("token-file", "'" + tokenFile + "' is not a readable file");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalid(name, "must be a positive duration, got " + value);
        }
    }

    private static IllegalArgumentException invalid(String property, String problem) {
        return new IllegalArgumentException(PREFIX + "." + property + " " + problem);
    }
}
