package com.swimpulse.pool;

import com.swimpulse.common.RedisJsonCacheService;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
	private final RedisJsonCacheService redisCache;
	private final Duration geocodeSuccessTtl;
	private final Duration geocodeFailureTtl;
	private final Duration reverseGeocodeSuccessTtl;
	private final Duration reverseGeocodeFailureTtl;

	public NaverMapsGeocodingClient(
			RestClient.Builder restClientBuilder,
			@Value("${swimpulse.naver.maps.client-id:}") String clientId,
			@Value("${swimpulse.naver.maps.client-secret:}") String clientSecret,
			RedisJsonCacheService redisCache,
			@Value("${swimpulse.cache.geocode-success-ttl:P30D}") Duration geocodeSuccessTtl,
			@Value("${swimpulse.cache.geocode-failure-ttl:P1D}") Duration geocodeFailureTtl,
			@Value("${swimpulse.cache.reverse-geocode-success-ttl:P7D}") Duration reverseGeocodeSuccessTtl,
			@Value("${swimpulse.cache.reverse-geocode-failure-ttl:P1D}") Duration reverseGeocodeFailureTtl
	) {
		this.restClient = restClientBuilder.build();
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.redisCache = redisCache;
		this.geocodeSuccessTtl = geocodeSuccessTtl;
		this.geocodeFailureTtl = geocodeFailureTtl;
		this.reverseGeocodeSuccessTtl = reverseGeocodeSuccessTtl;
		this.reverseGeocodeFailureTtl = reverseGeocodeFailureTtl;
	}

	public Optional<Coordinates> geocode(String address) {
		String cacheKey = "swimpulse:cache:geocode:v1:" + redisCache.hash(normalizeCacheInput(address));
		Optional<CachedCoordinates> cached = redisCache.get("geocode", cacheKey, CachedCoordinates.class);
		if (cached.isPresent()) {
			return cached.get().toCoordinates();
		}

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
			redisCache.put("geocode", cacheKey, CachedCoordinates.miss(), geocodeFailureTtl);
			return Optional.empty();
		}

		NaverAddress addressResult = response.addresses().getFirst();
		Coordinates coordinates = new Coordinates(
				Double.parseDouble(addressResult.y()),
				Double.parseDouble(addressResult.x())
		);
		redisCache.put("geocode", cacheKey, CachedCoordinates.hit(coordinates), geocodeSuccessTtl);
		log.info("Naver maps geocode completed. address={} latitude={} longitude={}",
				address, coordinates.latitude(), coordinates.longitude());
		return Optional.of(coordinates);
	}

	public Optional<String> reverseGeocode(double latitude, double longitude) {
		String cacheKey = "swimpulse:cache:reverse-geocode:v1:"
				+ bucket(latitude) + ":" + bucket(longitude);
		Optional<CachedAddress> cached = redisCache.get("reverse-geocode", cacheKey, CachedAddress.class);
		if (cached.isPresent()) {
			return cached.get().toAddress();
		}

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
			redisCache.put("reverse-geocode", cacheKey, CachedAddress.miss(), reverseGeocodeFailureTtl);
			return Optional.empty();
		}

		for (NaverReverseResult result : response.results()) {
			String address = formatReverseGeocodedAddress(result);
			if (hasText(address)) {
				redisCache.put("reverse-geocode", cacheKey, CachedAddress.hit(address), reverseGeocodeSuccessTtl);
				log.info("Naver maps reverse geocode completed. latitude={} longitude={} address={}",
						latitude, longitude, address);
				return Optional.of(address);
			}
		}
		log.info("Naver maps reverse geocode returned no address. latitude={} longitude={}", latitude, longitude);
		redisCache.put("reverse-geocode", cacheKey, CachedAddress.miss(), reverseGeocodeFailureTtl);
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

	private String normalizeCacheInput(String value) {
		return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private String bucket(double value) {
		return String.format(Locale.ROOT, "%.4f", value);
	}

	public record Coordinates(double latitude, double longitude) {
	}

	private record CachedCoordinates(boolean found, Double latitude, Double longitude) {
		private static CachedCoordinates hit(Coordinates coordinates) {
			return new CachedCoordinates(true, coordinates.latitude(), coordinates.longitude());
		}

		private static CachedCoordinates miss() {
			return new CachedCoordinates(false, null, null);
		}

		private Optional<Coordinates> toCoordinates() {
			return found && latitude != null && longitude != null
					? Optional.of(new Coordinates(latitude, longitude))
					: Optional.empty();
		}
	}

	private record CachedAddress(boolean found, String address) {
		private static CachedAddress hit(String address) {
			return new CachedAddress(true, address);
		}

		private static CachedAddress miss() {
			return new CachedAddress(false, null);
		}

		private Optional<String> toAddress() {
			return found && hasText(address) ? Optional.of(address) : Optional.empty();
		}

		private boolean hasText(String value) {
			return value != null && !value.isBlank();
		}
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
