package com.defold.extender;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {
	@Value("${extender.authentication.platforms}")
	String[] authenticatedPlatforms;

	private InMemoryUserDetailsManager userDetailsManager = new InMemoryUserDetailsManager();

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		for(String platform : authenticatedPlatforms) {
			switch(platform) {
				case "android":
					http.authorizeRequests().antMatchers("/build/armv7-android/**").hasRole("ANDROID").and().httpBasic();
					http.authorizeRequests().antMatchers("/build/arm64-android/**").hasRole("ANDROID").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/armv7-android/**").hasRole("ANDROID").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/arm64-android/**").hasRole("ANDROID").and().httpBasic();
					break;
				case "ios":
					http.authorizeRequests().antMatchers("/build/armv7-ios/**").hasRole("IOS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build/arm64-ios/**").hasRole("IOS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build/x86_64-ios/**").hasRole("IOS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/armv7-ios/**").hasRole("IOS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/arm64-ios/**").hasRole("IOS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/x86_64-ios/**").hasRole("IOS").and().httpBasic();
					break;
				case "linux":
					http.authorizeRequests().antMatchers("/build/x86_64-linux/**").hasRole("LINUX").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/x86_64-linux/**").hasRole("LINUX").and().httpBasic();
					break;
				case "macos":
					http.authorizeRequests().antMatchers("/build/x86_64-osx/**").hasRole("MACOS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/x86_64-osx/**").hasRole("MACOS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build/arm64-osx/**").hasRole("MACOS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/eram64-osx/**").hasRole("MACOS").and().httpBasic();
					break;
				case "windows":
					http.authorizeRequests().antMatchers("/build/x86_64-win32/**").hasRole("WINDOWS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build/x86-win32/**").hasRole("WINDOWS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/x86_64-win32/**").hasRole("WINDOWS").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/x86-win32/**").hasRole("WINDOWS").and().httpBasic();
					break;
				case "html5":
					http.authorizeRequests().antMatchers("/build/js-web/**").hasRole("HTML5").and().httpBasic();
					http.authorizeRequests().antMatchers("/build/wasm-web/**").hasRole("HTML5").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/js-web/**").hasRole("HTML5").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/wasm-web/**").hasRole("HTML5").and().httpBasic();
					break;
				case "switch":
					http.authorizeRequests().antMatchers("/build/arm64-nx64/**").hasRole("SWITCH").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/arm64-nx64/**").hasRole("SWITCH").and().httpBasic();
					break;
				case "ps4":
					http.authorizeRequests().antMatchers("/build/x86_64-ps4/**").hasRole("PS4").and().httpBasic();
					http.authorizeRequests().antMatchers("/build_async/x86_64-ps4/**").hasRole("PS4").and().httpBasic();
					break;
				case "ps5":
					http.authorizeRequests().antMatchers("/build/x86_64-ps5/**").hasRole("PS5").and().httpBasic();
					http.authorizeRequests().antMatchers("/build/_async/x86_64-ps5/**").hasRole("PS5").and().httpBasic();
					break;
			}
		}
		// if (authenticatedPlatforms.length > 0) {
		// 	http.authorizeRequests().antMatchers("/").hasRole("WATCHER").and().httpBasic();
		// 	http.authorizeRequests().antMatchers("/query").hasRole("CACHE").and().httpBasic();
		// 	http.authorizeRequests().antMatchers("/actuator/prometheus").hasRole("COLLECTOR").and().httpBasic();
		// }
		http.csrf().disable();
	}

	@Override
	protected void configure(AuthenticationManagerBuilder auth) throws Exception {
		auth.userDetailsService(userDetailsManager);
	}

	@Bean
	public InMemoryUserDetailsManager userDetailsManagerBean() {
		return userDetailsManager;
	}
}
