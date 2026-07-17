package com.swimpulse.config;

import com.swimpulse.auth.JwtAuthenticationFilter;
import com.swimpulse.auth.OAuthFailureHandler;
import com.swimpulse.auth.OAuthSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
	@Bean
	SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			JwtAuthenticationFilter jwtAuthenticationFilter,
			OAuthSuccessHandler oauthSuccessHandler,
			OAuthFailureHandler oauthFailureHandler
	) throws Exception {
		return http
				.csrf(AbstractHttpConfigurer::disable)
				.cors(Customizer.withDefaults())
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/mobile/google").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/pools", "/api/pools/nearby", "/api/events").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/pools/*/notices/scan", "/api/pools/{poolId}/notices/scan").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/pools/location-candidates").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/locations/search", "/api/locations/geocode", "/api/locations/reverse-geocode").permitAll()
						.requestMatchers("/api/admin/**").hasRole("ADMIN")
						.requestMatchers("/api/**").authenticated()
						.anyRequest().permitAll()
				)
				.oauth2Login(oauth2 -> oauth2
						.successHandler(oauthSuccessHandler)
						.failureHandler(oauthFailureHandler)
				)
				.logout(AbstractHttpConfigurer::disable)
				.exceptionHandling(exceptionHandling -> exceptionHandling
						.authenticationEntryPoint((request, response, exception) -> response.sendError(401))
				)
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}
