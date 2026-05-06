package com.swimpulse.pool;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class NaverMapsGeocodingClient {
	private static final String NAVER_MAPS_GEOCODING_URL = "https://maps.apigw.ntruss.com/map-geocode/v2/geocode";

	private final RestClient restClient;
	private final String clientId;
	private final String clientSecret;

	public NaverMapsGeocodingClient(
			RestClient.Builder restClientBuilder,
			@Value("${swimpulse.naver.maps.client-id:}") String clientId,
			@Value("${swimpulse.naver.maps.client-secret:}") String clientSecret
	) {
		this.restClient = restClientBuilder.build();
		this.clientId = clientId;
		this.clientSecret = clientSecret;
	}

	public Optional<Coordinates> geocode(String address) {
		URI uri = UriComponentsBuilder.fromUriString(NAVER_MAPS_GEOCODING_URL)
				.queryParam("query", address)
				.encode()
				.build()
				.toUri();

		NaverGeocodingResponse response = restClient.get()
				.uri(uri)
				.header("x-ncp-apigw-api-key-id", clientId)
				.header("x-ncp-apigw-api-key", clientSecret)
				.retrieve()
				.body(NaverGeocodingResponse.class);

		if (response == null || response.addresses() == null || response.addresses().isEmpty()) {
			return Optional.empty();
		}

		NaverAddress addressResult = response.addresses().getFirst();
		return Optional.of(new Coordinates(
				Double.parseDouble(addressResult.y()),
				Double.parseDouble(addressResult.x())
		));
	}

	public boolean isConfigured() {
		return hasText(clientId) && hasText(clientSecret);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public record Coordinates(double latitude, double longitude) {
	}

	public record NaverGeocodingResponse(String status, List<NaverAddress> addresses) {
	}

	public record NaverAddress(String x, String y) {
	}
}
