package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.defold.extender.jetty.JettyServerConfiguration;

/**
 * Runs a real Jetty, because the reported status depends on how the rejected multipart upload is
 * handled by the servlet container (see defold/extender#1092).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                classes = PlatformAuthorizationTest.TestApp.class,
                properties = "extender.authentication.platforms=linux,xbox")
public class PlatformAuthorizationTest {

    private static final String BUILD_LINUX = "/build_async/x86_64-linux/sdk";
    private static final String BUILD_XBOX = "/build_async/x86_64-xbone/sdk";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ JettyServerConfiguration.class, WebSecurityConfig.class, RequestErrorAdvice.class })
    static class TestApp {
        @Bean
        BuildController buildController() {
            return new BuildController();
        }
    }

    @RestController
    static class BuildController {
        @PostMapping("/build_async/{platform}/{sdkVersion}")
        String build(@PathVariable String platform, MultipartHttpServletRequest request) {
            return platform;
        }
    }

    @LocalServerPort
    private int port;

    @BeforeAll
    public static void createUsers(@Autowired InMemoryUserDetailsManager userDetailsManager) {
        userDetailsManager.createUser(User.builder().username("linuxuser").password("{noop}secret").authorities("ROLE_LINUX").build());
    }

    @Test
    public void buildWithAccessToThePlatformIsAccepted() throws IOException {
        Response response = build(BUILD_LINUX, "linuxuser", "secret");
        assertEquals(200, response.status());
        assertEquals("x86_64-linux", response.body());
    }

    @Test
    public void buildWithoutCredentialsIsUnauthorized() throws IOException {
        assertInvalidCredentials(build(BUILD_LINUX, null, null));
    }

    @Test
    public void buildWithWrongPasswordIsUnauthorized() throws IOException {
        assertInvalidCredentials(build(BUILD_LINUX, "linuxuser", "wrong"));
    }

    @Test
    public void buildForPlatformTheUserHasNoAccessToIsUnauthorized() throws IOException {
        Response response = build(BUILD_XBOX, "linuxuser", "secret");
        assertUnauthorized(response);
        assertEquals("User 'linuxuser' does not have access to platform 'x86_64-xbone'.", response.body());
    }

    private static void assertInvalidCredentials(Response response) {
        assertUnauthorized(response);
        assertTrue(response.body().startsWith("Missing or invalid credentials."), response.body());
    }

    private static void assertUnauthorized(Response response) {
        assertEquals(401, response.status());
        assertEquals("Basic realm=\"Extender\"", response.wwwAuthenticate());
        assertTrue(response.contentType().startsWith("text/plain"), response.contentType());
    }

    private record Response(int status, String wwwAuthenticate, String contentType, String body) {}

    private Response build(String path, String user, String password) throws IOException {
        HttpPost request = new HttpPost(String.format("http://localhost:%d%s", port, path));
        if (user != null) {
            String credentials = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            request.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials);
        }
        request.setEntity(MultipartEntityBuilder.create()
                .addBinaryBody("ext.manifest", "name: test".getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_OCTET_STREAM, "ext.manifest")
                .build());
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpResponse response = client.execute(request);
            Header wwwAuthenticate = response.getFirstHeader(HttpHeaders.WWW_AUTHENTICATE);
            Header contentType = response.getEntity().getContentType();
            return new Response(response.getStatusLine().getStatusCode(),
                    wwwAuthenticate == null ? "" : wwwAuthenticate.getValue(),
                    contentType == null ? "" : contentType.getValue(),
                    EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8).trim());
        }
    }
}
