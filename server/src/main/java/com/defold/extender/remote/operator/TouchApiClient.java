package com.defold.extender.remote.operator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import com.defold.extender.remote.operator.TouchApiResponse.Failure;
import com.defold.extender.remote.operator.TouchApiResponse.Kind;
import com.defold.extender.remote.operator.TouchApiResponse.Ok;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP client for the operator's touch API. One call = one request: retries and
 * waiting are the controller's job, so automatic retries are off.
 *
 * <p>Application Security Requirement: the bearer token goes only to the configured base URL —
 * redirects are never followed, no system proxy settings, builder names are DNS-1123-validated
 * before they reach the path, response bodies are size-capped and error details never include the
 * token or request headers.
 */
public final class TouchApiClient implements AutoCloseable {

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_CONNECTIONS = 20;
    static final int MAX_BODY_BYTES = 4096;
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);
    static final Duration IDLE_EVICTION = Duration.ofSeconds(30);
    private static final Pattern BUILDER_NAME = Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,40}");

    private final URI base;
    private final TokenProvider tokens;
    private final CloseableHttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public TouchApiClient(URI base, TokenProvider tokens) {
        this(base, tokens, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    TouchApiClient(URI base, TokenProvider tokens, Duration connectTimeout, Duration readTimeout) {
        this.base = Objects.requireNonNull(base, "base");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        int connectMs = Math.toIntExact(connectTimeout.toMillis());
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(connectMs)
                .setConnectionRequestTimeout(connectMs)
                .setSocketTimeout(Math.toIntExact(readTimeout.toMillis()))
                .setRedirectsEnabled(false)
                .build();
        PoolingHttpClientConnectionManager pool = new PoolingHttpClientConnectionManager();
        pool.setMaxTotal(MAX_CONNECTIONS);
        pool.setDefaultMaxPerRoute(MAX_CONNECTIONS);
        pool.setValidateAfterInactivity(2_000);
        this.http = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(config)
                // The operator's server closes idle keep-alive connections after 60s; drop ours first so
                // a request never races that close (retries are off, it would surface as a failure).
                .evictIdleConnections(IDLE_EVICTION.toSeconds(), TimeUnit.SECONDS)
                .setConnectionTimeToLive(IDLE_EVICTION.toSeconds(), TimeUnit.SECONDS)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .setUserAgent("defold-extender")
                .build();
    }

    /** {@code POST /v1/builders/{name}/touch}: heartbeat + "ensure at least one replica". */
    public TouchApiResponse touch(String name) {
        return call(new HttpPost(uri(name, "touch")));
    }

    /** {@code GET /v1/builders/{name}/state}. */
    public TouchApiResponse state(String name) {
        return call(new HttpGet(uri(name, "state")));
    }

    /** Whether {@code name} can be an ExtenderBuilder name (DNS-1123 label), i.e. safe in the path. */
    public static boolean validName(String name) {
        return name != null && BUILDER_NAME.matcher(name).matches();
    }

    private URI uri(String name, String action) {
        if (!validName(name)) {
            throw new IllegalArgumentException("builder name is not a DNS-1123 label: '" + name + "'");
        }
        return URI.create(base + "/v1/builders/" + name + "/" + action);
    }

    private TouchApiResponse call(HttpRequestBase request) {
        request.setHeader("Authorization", "Bearer " + tokens.token());
        request.setHeader("Accept", "application/json");
        try (CloseableHttpResponse response = http.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            return classify(status, read(response.getEntity()), response.getFirstHeader("Retry-After"));
        } catch (IOException e) {
            return Failure.of(Kind.UNREACHABLE, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (IllegalStateException e) {
            // Pool already shut down (router stopping): don't leak a RuntimeException into the health report.
            return Failure.of(Kind.UNREACHABLE, 0, "client closed");
        }
    }

    private TouchApiResponse classify(int status, byte[] body, Header retryAfter) {
        return switch (status) {
            case 200, 202 -> parseState(status, body);
            case 401 -> Failure.of(Kind.UNAUTHORIZED, status, "token rejected");
            case 404 -> Failure.of(Kind.UNKNOWN_BUILDER, status, errorCode(body));
            case 429, 500, 502, 503, 504 ->
                    new Failure(Kind.RETRYABLE, status, errorCode(body), parseRetryAfter(retryAfter));
            default -> Failure.of(Kind.REJECTED, status, errorCode(body));
        };
    }

    private TouchApiResponse parseState(int status, byte[] body) {
        try {
            JsonNode node = json.readTree(body);
            JsonNode phase = node.get("phase");
            Optional<Phase> parsed = phase != null && phase.isString() ? Phase.parse(phase.stringValue()) : Optional.empty();
            if (parsed.isEmpty()) {
                return Failure.of(Kind.REJECTED, status, "response has no known phase");
            }
            JsonNode alwaysOn = node.get("alwaysOn");
            return new Ok(new BuilderState(parsed.get(), alwaysOn != null && alwaysOn.isBoolean() && alwaysOn.booleanValue()));
        } catch (JacksonException e) {
            return Failure.of(Kind.REJECTED, status, "response is not JSON");
        }
    }

    /** The operator's short error code, if it is one; never echoes arbitrary body content. */
    private String errorCode(byte[] body) {
        try {
            JsonNode error = json.readTree(body).get("error");
            if (error != null && error.isString() && ERROR_CODE.matcher(error.stringValue()).matches()) {
                return error.stringValue();
            }
        } catch (JacksonException e) {
            // fall through: not the operator's JSON error shape
        }
        return "unexpected response";
    }

    private static Optional<Duration> parseRetryAfter(Header header) {
        if (header == null) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(header.getValue().trim());
            if (seconds < 0) {
                return Optional.empty();
            }
            Duration value = Duration.ofSeconds(seconds);
            return Optional.of(value.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : value);
        } catch (NumberFormatException e) {
            return Optional.empty(); // HTTP-date form: fall back to the poll interval
        }
    }

    private static byte[] read(HttpEntity entity) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        try (InputStream in = entity.getContent()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IOException("response body larger than " + MAX_BODY_BYTES + " bytes");
            }
            return bytes;
        }
    }

    @Override
    public void close() throws IOException {
        http.close();
    }

    @Override
    public String toString() {
        return "TouchApiClient[" + base + "]";
    }
}
