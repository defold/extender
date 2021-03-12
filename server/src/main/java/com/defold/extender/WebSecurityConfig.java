package com.defold.extender;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;


@Configuration
@EnableWebSecurity
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {
	@Value("file:users.txt")
	Resource usersResource;

	private Properties loadUsers() throws Exception {
		Properties users = new Properties();
		if (usersResource != null) {
			users.load(usersResource.getInputStream());
		}
		return users;
	}

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		Properties users = loadUsers();
		if (!users.isEmpty()) {
			http.authorizeRequests().antMatchers("/build/armv7-android/**").hasRole("ANDROID").and().httpBasic();
			http.authorizeRequests().antMatchers("/build/arm64-android/**").hasRole("ANDROID").and().httpBasic();

			http.authorizeRequests().antMatchers("/build/armv7-ios:/**").hasRole("IOS").and().httpBasic();
			http.authorizeRequests().antMatchers("/build/arm64-ios/**").hasRole("IOS").and().httpBasic();
			http.authorizeRequests().antMatchers("/build/x86_64-ios/**").hasRole("IOS").and().httpBasic();

			http.authorizeRequests().antMatchers("/build/x86_64-linux/**").hasRole("LINUX").and().httpBasic();

			http.authorizeRequests().antMatchers("/build/x86_64-osx/**").hasRole("MACOS").and().httpBasic();

			http.authorizeRequests().antMatchers("/build/x86_64-win32/**").hasRole("WINDOWS").and().httpBasic();
			http.authorizeRequests().antMatchers("/build/x86-win32/**").hasRole("WINDOWS").and().httpBasic();

			http.authorizeRequests().antMatchers("/build/js-web/**").hasRole("HTML5").and().httpBasic();
			http.authorizeRequests().antMatchers("/build/wasm-web/**").hasRole("HTML5").and().httpBasic();

			http.authorizeRequests().antMatchers("/build/arm64-nx64/**").hasRole("SWITCH").and().httpBasic();
		}
		http.csrf().disable();
	}

	@Override
	protected void configure(AuthenticationManagerBuilder auth) throws Exception {
		Properties users = loadUsers();
		auth.userDetailsService(new InMemoryUserDetailsManager(users));
	}
}
