package com.defold.extender;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {
    @Value("${extender.authentication.platforms}")
    String[] authenticatedPlatforms;

    private InMemoryUserDetailsManager userDetailsManager = new InMemoryUserDetailsManager();

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        for(String platform : authenticatedPlatforms) {
            switch(platform) {
                case "android":
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/armv7-android/**").hasRole("ANDROID")).httpBasic(withDefaults());
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/arm64-android/**").hasRole("ANDROID")).httpBasic(withDefaults());
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/x86_64-android/**").hasRole("ANDROID")).httpBasic(withDefaults());
                    break;
                case "ios":
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/armv7-ios/**").hasRole("IOS")).httpBasic(withDefaults());
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/arm64-ios/**").hasRole("IOS")).httpBasic(withDefaults());
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/arm64_sim-ios/**").hasRole("IOS")).httpBasic(withDefaults());
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/x86_64-ios/**").hasRole("IOS")).httpBasic(withDefaults());
                    break;
                case "linux":
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/x86_64-linux/**").hasRole("LINUX")).httpBasic(withDefaults());
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/arm64-linux/**").hasRole("LINUX")).httpBasic(withDefaults());
                    break;
                case "macos":
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/x86_64-osx/**").hasRole("MACOS")).httpBasic(withDefaults());
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/arm64-osx/**").hasRole("MACOS")).httpBasic(withDefaults());
                    break;
                case "windows":
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/x86_64-win32/**").hasRole("WINDOWS")).httpBasic(withDefaults());
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/x86-win32/**").hasRole("WINDOWS")).httpBasic(withDefaults());
                    break;
                case "html5":
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/js-web/**").hasRole("HTML5")).httpBasic(withDefaults());
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/wasm-web/**").hasRole("HTML5")).httpBasic(withDefaults());
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/wasm_pthread-web/**").hasRole("HTML5")).httpBasic(withDefaults());
                    break;
                case "switch":
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/arm64-nx64/**").hasRole("SWITCH")).httpBasic(withDefaults());
                    break;
                case "ps4":
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/x86_64-ps4/**").hasRole("PS4")).httpBasic(withDefaults());
                    break;
                case "ps5":
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/x86_64-ps5/**").hasRole("PS5")).httpBasic(withDefaults());
                    break;
                case "xbox":
                    http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.requestMatchers("/build_async/x86_64-xbone/**").hasRole("XBOX")).httpBasic(withDefaults());
                    break;
            }
        }
        // if (authenticatedPlatforms.length > 0) {
        // 	http.authorizeRequests().requestMatchers("/").hasRole("WATCHER").and().httpBasic();
        // 	http.authorizeRequests().requestMatchers("/query").hasRole("CACHE").and().httpBasic();
        // 	http.authorizeRequests().requestMatchers("/actuator/prometheus").hasRole("COLLECTOR").and().httpBasic();
        // }
        if (authenticatedPlatforms.length > 0) {
            // Write the responses directly: sendError() would forward to /error, which tries to parse the
            // already discarded multipart body and answers with 400 instead (defold/extender#1092).
            http.httpBasic(basic -> basic.authenticationEntryPoint(WebSecurityConfig::rejectInvalidCredentials))
                .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint(WebSecurityConfig::rejectInvalidCredentials)
                    .accessDeniedHandler(WebSecurityConfig::rejectPlatformAccess));
        }
        http.authorizeHttpRequests((authorizeHttpRequest) -> authorizeHttpRequest.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    InMemoryUserDetailsManager userDetailsManagerBean() {
        return userDetailsManager;
    }

    private static void rejectInvalidCredentials(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        reject(response, "Missing or invalid credentials. Check the user name and password of the build server.");
    }

    private static void rejectPlatformAccess(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) throws IOException {
        final String user = request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "";
        reject(response, String.format("User '%s' does not have access to platform '%s'.", user, platformOf(request)));
    }

    // /build_async/{platform}/...
    private static String platformOf(HttpServletRequest request) {
        final String[] segments = request.getRequestURI().split("/");
        return segments.length > 2 ? segments[2] : "";
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Extender\"");
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(message);
    }
}
