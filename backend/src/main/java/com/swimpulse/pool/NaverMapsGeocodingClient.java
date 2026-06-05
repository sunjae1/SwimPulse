package com.swimpulse.pool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class NaverMapsGeocodingClient {
	private static final Logger log = LoggerFactory.getLogger(NaverMapsGeocodingClient.class);
	private static final String NAVER_MAPS_GEOCODING_URL = "https://maps.apigw.ntruss.com/map-geocode/v2/geocode";
	private static final String NAVER_MAPS_REVERSE_GEOCODING_URL = "https://maps.apigw.ntruss.com/map-reversegeocode/v2/gc";

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
		log.info("Naver maps geocode requested. address={}", address);
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
			log.info("Naver maps geocode returned no coordinates. address={}", address);
			return Optional.empty();
		}

		NaverAddress addressResult = response.addresses().getFirst();
		Coordinates coordinates = new Coordinates(
				Double.parseDouble(addressResult.y()),
				Double.parseDouble(addressResult.x())
		);
		log.info("Naver maps geocode completed. address={} latitude={} longitude={}",
				address, coordinates.latitude(), coordinates.longitude());
		return Optional.of(coordinates);
	}

	public Optional<String> reverseGeocode(double latitude, double longitude) {
		log.info("Naver maps reverse geocode requested. latitude={} longitude={}", latitude, longitude);
		URI uri = UriComponentsBuilder.fromUriString(NAVER_MAPS_REVERSE_GEOCODING_URL)
				.queryParam("request", "coordsToaddr")
				.queryParam("coords", longitude + "," + latitude)
				.queryParam("sourcecrs", "epsg:4326")
				.queryParam("orders", "roadaddr,addr")
				.queryParam("output", "json")
				.encode()
				.build()
				.toUri();

		NaverReverseGeocodingResponse response = restClient.get()
				.uri(uri)
				.header("x-ncp-apigw-api-key-id", clientId)
				.header("x-ncp-apigw-api-key", clientSecret)
				.retrieve()
				.body(NaverReverseGeocodingResponse.class);

		if (response == null || response.results() == null) {
			return Optional.empty();
		}

		for (NaverReverseResult result : response.results()) {
			String address = formatReverseGeocodedAddress(result);
			if (hasText(address)) {
				log.info("Naver maps reverse geocode completed. latitude={} longitude={} address={}",
						latitude, longitude, address);
				return Optional.of(address);
			}
		}
		log.info("Naver maps reverse geocode returned no address. latitude={} longitude={}", latitude, longitude);
		return Optional.empty();
	}

	public boolean isConfigured() {
		return hasText(clientId) && hasText(clientSecret);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private String formatReverseGeocodedAddress(NaverReverseResult result) {
		String resultName = result.name();
		NaverReverseLand land = result.land();
		NaverReverseRegion region = result.region();
		List<String> parts = new ArrayList<>();

		addIfPresent(parts, areaName(region == null ? null : region.area1()));
		addIfPresent(parts, areaName(region == null ? null : region.area2()));
		if (!"roadaddr".equals(resultName)) {
			addIfPresent(parts, areaName(region == null ? null : region.area3()));
			addIfPresent(parts, areaName(region == null ? null : region.area4()));
		}

		if ("roadaddr".equals(resultName)) {
			String roadName = land == null ? null : land.name();
			String buildingNumber = joinAddressNumber(land == null ? null : land.number1(), land == null ? null : land.number2());
			if (hasText(roadName) && hasText(buildingNumber)) {
				parts.add(roadName + " " + buildingNumber);
			} else {
				addIfPresent(parts, roadName);
				addIfPresent(parts, buildingNumber);
			}
			addIfPresent(parts, firstLandAdditionValue(land));
		} else {
			addIfPresent(parts, joinAddressNumber(land == null ? null : land.number1(), land == null ? null : land.number2()));
		}

		return String.join(" ", parts).replaceAll("\\s+", " ").trim();
	}

	private String firstLandAdditionValue(NaverReverseLand land) {
		if (land == null) {
			return null;
		}
		for (NaverReverseAddition addition : List.of(land.addition0(), land.addition1(), land.addition2(), land.addition3(), land.addition4())) {
			if (addition != null && "building".equals(addition.type()) && hasText(addition.value())) {
				return addition.value();
			}
		}
		return null;
	}

	private String joinAddressNumber(String number1, String number2) {
		if (!hasText(number1)) {
			return null;
		}
		if (!hasText(number2) || "0".equals(number2)) {
			return number1;
		}
		return number1 + "-" + number2;
	}

	private void addIfPresent(List<String> parts, String value) {
		if (hasText(value)) {
			parts.add(value.trim());
		}
	}

	private String areaName(NaverReverseArea area) {
		return area == null ? null : area.name();
	}

	public record Coordinates(double latitude, double longitude) {
	}

	public record NaverGeocodingResponse(String status, List<NaverAddress> addresses) {
	}

	public record NaverAddress(String x, String y) {
	}

	public record NaverReverseGeocodingResponse(List<NaverReverseResult> results) {
	}

	public record NaverReverseResult(String name, NaverReverseRegion region, NaverReverseLand land) {
	}

	public record NaverReverseRegion(
			NaverReverseArea area1,
			NaverReverseArea area2,
			NaverReverseArea area3,
			NaverReverseArea area4
	) {
	}

	public record NaverReverseArea(String name) {
	}

	public record NaverReverseLand(
			String name,
			String number1,
			String number2,
			NaverReverseAddition addition0,
			NaverReverseAddition addition1,
			NaverReverseAddition addition2,
			NaverReverseAddition addition3,
			NaverReverseAddition addition4
	) {
	}

	public record NaverReverseAddition(String type, String value) {
	}
}
