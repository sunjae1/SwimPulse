package com.swimpulse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {
	@Bean
	RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}

	@Bean
	ObjectMapper objectMapper() {
		return new ObjectMapper()
				.registerModule(new JavaTimeModule())
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // ISO 문자열 사용. "2026-04-27T00:00:00Z"
	}
}
