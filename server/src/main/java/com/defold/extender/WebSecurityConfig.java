package com.defold.extender;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {
	@Value("${extender.authentication.platforms}")
	String[] authenticatedPlatforms;

	private InMemoryUserDetailsManager userDetailsManager = new InMemoryUserDetailsManager();

	@Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		for(String platform : authenticatedPlatforms) {
			switch(platform) {
				case "android":
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/armv7-android/**").hasRole("ANDROID")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/arm64-android/**").hasRole("ANDROID")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/armv7-android/**").hasRole("ANDROID")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/arm64-android/**").hasRole("ANDROID")).httpBasic(withDefaults());
					break;
				case "ios":
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/armv7-ios/**").hasRole("IOS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/arm64-ios/**").hasRole("IOS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/x86_64-ios/**").hasRole("IOS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/armv7-ios/**").hasRole("IOS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/arm64-ios/**").hasRole("IOS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/x86_64-ios/**").hasRole("IOS")).httpBasic(withDefaults());
					break;
				case "linux":
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/x86_64-linux/**").hasRole("LINUX")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/x86_64-linux/**").hasRole("LINUX")).httpBasic(withDefaults());
					break;
				case "macos":
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/x86_64-osx/**").hasRole("MACOS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/x86_64-osx/**").hasRole("MACOS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/arm64-osx/**").hasRole("MACOS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/arm64-osx/**").hasRole("MACOS")).httpBasic(withDefaults());
					break;
				case "windows":
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/x86_64-win32/**").hasRole("WINDOWS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/x86-win32/**").hasRole("WINDOWS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/x86_64-win32/**").hasRole("WINDOWS")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/x86-win32/**").hasRole("WINDOWS")).httpBasic(withDefaults());
					break;
				case "html5":
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/js-web/**").hasRole("HTML5")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/wasm-web/**").hasRole("HTML5")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/js-web/**").hasRole("HTML5")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/wasm-web/**").hasRole("HTML5")).httpBasic(withDefaults());
					break;
				case "switch":
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/arm64-nx64/**").hasRole("SWITCH")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/arm64-nx64/**").hasRole("SWITCH")).httpBasic(withDefaults());
					break;
				case "ps4":
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/x86_64-ps4/**").hasRole("PS4")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build_async/x86_64-ps4/**").hasRole("PS4")).httpBasic(withDefaults());
					break;
				case "ps5":
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/x86_64-ps5/**").hasRole("PS5")).httpBasic(withDefaults());
                    http.authorizeRequests((requests) -> requests.requestMatchers("/build/_async/x86_64-ps5/**").hasRole("PS5")).httpBasic(withDefaults());
					break;
			}
		}
        // if (authenticatedPlatforms.length > 0) {
        // 	http.authorizeRequests().requestMatchers("/").hasRole("WATCHER").and().httpBasic();
        // 	http.authorizeRequests().requestMatchers("/query").hasRole("CACHE").and().httpBasic();
        // 	http.authorizeRequests().requestMatchers("/actuator/prometheus").hasRole("COLLECTOR").and().httpBasic();
        // }
        http.csrf(csrf -> csrf.disable());
        return http.build();
	}

	@Bean
	public InMemoryUserDetailsManager userDetailsManagerBean() {
		return userDetailsManager;
	}
}
