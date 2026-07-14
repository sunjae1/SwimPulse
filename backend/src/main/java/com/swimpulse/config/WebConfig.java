package com.swimpulse.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
	private final String[] allowedOriginPatterns;

	public WebConfig(
			@Value("${swimpulse.cors.allowed-origin-patterns:http://localhost:3000,http://127.0.0.1:3000,https://unnamable-preset-contact.ngrok-free.dev,https://*.ngrok-free.dev,https://*.ngrok-free.app}")
			String allowedOriginPatterns
	) {
		this.allowedOriginPatterns = Arrays.stream(allowedOriginPatterns.split(","))
				.map(String::trim)
				.filter(pattern -> !pattern.isBlank())
				.toArray(String[]::new);
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOriginPatterns(allowedOriginPatterns)
				.allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*")
				.allowCredentials(true);
	}
}
