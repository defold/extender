package com.defold.extender.jetty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.defold.extender.RequestErrorAdvice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Runs a real Jetty (MockMvc never runs Jetty's multipart parser, so it cannot reproduce any of
 * this) on a random port, with a minimal context instead of the full Extender application. Requests
 * are sent with the same HTTP client the Extender client and the remote builder use.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                classes = JettyErrorHandlingTest.TestApp.class,
                properties = {
                    "server.jetty.max-form-keys=50",
                    "server.max-http-request-header-size=16KB"
                })
// The log assertions below observe loggers shared by the whole JVM, so the test methods of this
// class must not run next to each other (parallel execution is on by default, see
// junit-platform.properties).
@Execution(ExecutionMode.SAME_THREAD)
public class JettyErrorHandlingTest {

    private static final int MAX_PARTS = 50;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ JettyServerConfiguration.class, RequestErrorAdvice.class })
    static class TestApp {

        @Bean
        SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
            return http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }

        @Bean
        UploadController uploadController() {
            return new UploadController();
        }
    }

    @RestController
    static class UploadController {
        @PostMapping("/upload")
        String upload(MultipartHttpServletRequest request) {
            return String.valueOf(request.getFileMap().size());
        }
    }

    private static final List<Class<?>> LOGGERS_UNDER_TEST = List.of(
            ExtenderJettyErrorHandler.class, JettyRequestEventsHandler.class, RequestErrorAdvice.class);

    @LocalServerPort
    private int port;

    private final List<String> loggedMessages = new CopyOnWriteArrayList<>();
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            loggedMessages.add(event.getFormattedMessage());
        }
    };

    @BeforeEach
    public void captureLogs() {
        loggedMessages.clear();
        appender.start();
        LOGGERS_UNDER_TEST.forEach(logger -> ((Logger)LoggerFactory.getLogger(logger)).addAppender(appender));
    }

    @AfterEach
    public void releaseLogs() {
        LOGGERS_UNDER_TEST.forEach(logger -> ((Logger)LoggerFactory.getLogger(logger)).detachAppender(appender));
        appender.stop();
    }

    @Test
    public void uploadWithTooManyFilesIsRejectedWithAnExplanation() throws IOException {
        Response response = upload(MAX_PARTS + 1);

        assertEquals(413, response.status());
        assertTrue(response.contentType().startsWith("text/plain"), "Unexpected content type: " + response.contentType());
        assertTrue(response.body().contains(String.format("too many files (%d, the limit is %d)", MAX_PARTS + 1, MAX_PARTS)),
                "Unexpected body: " + response.body());

        assertLogged("too many files");
        // The request completed inside the servlet context, so this also proves that the events
        // handler is part of the handler chain.
        assertLogged("completed with status 413");
    }

    @Test
    public void uploadWithinTheLimitSucceeds() throws IOException {
        Response response = upload(MAX_PARTS - 1);

        assertEquals(200, response.status());
        assertEquals(String.valueOf(MAX_PARTS - 1), response.body());
    }

    @Test
    public void malformedMultipartBodiesAreRejectedWithAnExplanation() throws IOException {
        // A body that never contains the announced boundary. Jetty fails to parse it before the
        // request is mapped to a controller method.
        String body = "not a multipart body\r\n";
        String response = sendRaw("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: multipart/form-data; boundary=aBoundary\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "\r\n"
                + body);

        assertTrue(response.startsWith("HTTP/1.1 400"), "Unexpected response: " + response);
        assertTrue(response.contains("not a valid multipart request"), "Unexpected response: " + response);
        assertLogged("not a valid multipart request");
    }

    @Test
    public void oversizedHeadersAreRejectedByTheJettyErrorHandler() throws IOException {
        HttpGet request = new HttpGet(url("/upload"));
        request.addHeader("X-Too-Large", "a".repeat(32 * 1024));

        Response response = execute(request);

        assertEquals(431, response.status());
        assertTrue(response.contentType().startsWith("text/plain"), "Unexpected content type: " + response.contentType());
        assertTrue(response.body().contains("The request headers are too large"), "Unexpected body: " + response.body());
        assertLogged("Jetty rejected request GET /upload");
    }

    @Test
    public void malformedRequestsAreRejectedByTheJettyErrorHandler() throws IOException {
        String response = sendRaw("GET / HTTP/1.1\r\nHost: localhost\r\nthis-is-not-a-header\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 400"), "Unexpected response: " + response);
        assertTrue(response.contains("text/plain"), "Unexpected response: " + response);
        assertTrue(response.contains("The server could not parse the request"), "Unexpected response: " + response);
        assertLogged("Jetty rejected request");
    }

    private record Response(int status, String contentType, String body) {}

    /**
     * Some of these messages are logged after the response has been written (the events handler is
     * notified when the request is recycled), so give them a moment to arrive.
     */
    private void assertLogged(String message) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (loggedMessages.stream().anyMatch(logged -> logged.contains(message))) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(String.format("Expected a log message containing '%s', but got: %s", message, loggedMessages));
    }

    private String url(String path) {
        return String.format("http://localhost:%d%s", port, path);
    }

    private Response upload(int fileCount) throws IOException {
        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();
        for (int i = 0; i < fileCount; i++) {
            final String filename = String.format("file%d.txt", i);
            entityBuilder.addBinaryBody(filename, filename.getBytes(StandardCharsets.UTF_8),
                    ContentType.APPLICATION_OCTET_STREAM, filename);
        }

        HttpPost request = new HttpPost(url("/upload"));
        request.setEntity(entityBuilder.build());
        return execute(request);
    }

    private Response execute(HttpUriRequest request) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpResponse response = client.execute(request);
            String contentType = response.getEntity().getContentType() == null
                    ? "" : response.getEntity().getContentType().getValue();
            return new Response(response.getStatusLine().getStatusCode(), contentType,
                    EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8).trim());
        }
    }

    private String sendRaw(String request) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append('\n');
                }
            }
            return response.toString();
        }
    }
}
