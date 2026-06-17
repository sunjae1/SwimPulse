package com.swimpulse.pool;

import com.swimpulse.common.RedisJsonCacheService;
import com.swimpulse.common.RedisSingleFlightService;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
	private final RedisSingleFlightService singleFlightService;
	private final Duration geocodeSuccessTtl;
	private final Duration geocodeFailureTtl;
	private final Duration reverseGeocodeSuccessTtl;
	private final Duration reverseGeocodeFailureTtl;
	private final Duration singleFlightLockTtl;
	private final Duration singleFlightWaitTimeout;
	private final Duration singleFlightPollInterval;

	public NaverMapsGeocodingClient(
			RestClient.Builder restClientBuilder,
			@Value("${swimpulse.naver.maps.client-id:}") String clientId,
			@Value("${swimpulse.naver.maps.client-secret:}") String clientSecret,
			RedisJsonCacheService redisCache,
			RedisSingleFlightService singleFlightService,
			@Value("${swimpulse.cache.geocode-success-ttl:P30D}") Duration geocodeSuccessTtl,
			@Value("${swimpulse.cache.geocode-failure-ttl:P1D}") Duration geocodeFailureTtl,
			@Value("${swimpulse.cache.reverse-geocode-success-ttl:P7D}") Duration reverseGeocodeSuccessTtl,
			@Value("${swimpulse.cache.reverse-geocode-failure-ttl:P1D}") Duration reverseGeocodeFailureTtl,
			@Value("${swimpulse.cache.single-flight-lock-ttl-ms:3000}") long singleFlightLockTtlMs,
			@Value("${swimpulse.cache.single-flight-wait-timeout-ms:2000}") long singleFlightWaitTimeoutMs,
			@Value("${swimpulse.cache.single-flight-poll-ms:50}") long singleFlightPollMs
	) {
		this.restClient = restClientBuilder.build();
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.redisCache = redisCache;
		this.singleFlightService = singleFlightService;
		this.geocodeSuccessTtl = geocodeSuccessTtl;
		this.geocodeFailureTtl = geocodeFailureTtl;
		this.reverseGeocodeSuccessTtl = reverseGeocodeSuccessTtl;
		this.reverseGeocodeFailureTtl = reverseGeocodeFailureTtl;
		this.singleFlightLockTtl = Duration.ofMillis(singleFlightLockTtlMs);
		this.singleFlightWaitTimeout = Duration.ofMillis(singleFlightWaitTimeoutMs);
		this.singleFlightPollInterval = Duration.ofMillis(singleFlightPollMs);
	}

	public Optional<Coordinates> geocode(String address) {
		String rawKey = normalizeCacheInput(address);
		String cacheKey = geocodeCacheKey(rawKey);
		log.debug("Naver maps geocode cache lookup. cache=geocode rawKey={} cacheKey={}", rawKey, cacheKey);
		return singleFlightService.getOrLoad(
				"geocode",
				cacheKey,
				CachedCoordinates.class,
				singleFlightLockTtl,
				singleFlightWaitTimeout,
				singleFlightPollInterval,
				() -> requestGeocode(address),
				cachedCoordinates -> redisCache.put(
						"geocode",
						cacheKey,
						cachedCoordinates,
						cachedCoordinates.found() ? geocodeSuccessTtl : geocodeFailureTtl
				)
		).toCoordinates();
	}

	public Map<String, GeocodeBatchResult> geocodeAll(Collection<String> addresses, int maxConcurrency) {
		LinkedHashMap<String, String> addressByCacheKey = new LinkedHashMap<>();
		for (String address : addresses) {
			if (!hasText(address)) {
				continue;
			}
			String normalized = normalizeCacheInput(address);
			addressByCacheKey.putIfAbsent(geocodeCacheKey(normalized), address);
		}
		if (addressByCacheKey.isEmpty()) {
			return Map.of();
		}

		Map<String, CachedCoordinates> cachedByKey = redisCache.getMany(
				"geocode",
				List.copyOf(addressByCacheKey.keySet()),
				CachedCoordinates.class
		);
		Map<String, GeocodeBatchResult> resultsByKey = new LinkedHashMap<>();
		cachedByKey.forEach((key, value) -> resultsByKey.put(key, GeocodeBatchResult.from(value)));

		List<Map.Entry<String, String>> misses = addressByCacheKey.entrySet()
				.stream()
				.filter(entry -> !cachedByKey.containsKey(entry.getKey()))
				.toList();
		if (!misses.isEmpty()) {
			resultsByKey.putAll(loadMissingGeocodes(misses, Math.max(1, maxConcurrency)));
		}

		Map<String, GeocodeBatchResult> resultsByAddress = new LinkedHashMap<>();
		for (String address : addresses) {
			if (!hasText(address)) {
				continue;
			}
			String cacheKey = geocodeCacheKey(normalizeCacheInput(address));
			GeocodeBatchResult result = resultsByKey.get(cacheKey);
			if (result != null) {
				resultsByAddress.put(address, result);
			}
		}
		return resultsByAddress;
	}

	private Map<String, GeocodeBatchResult> loadMissingGeocodes(
			List<Map.Entry<String, String>> misses,
			int maxConcurrency
	) {
		ExecutorService executor = Executors.newFixedThreadPool(maxConcurrency);
		try {
			List<CompletableFuture<Map.Entry<String, GeocodeBatchResult>>> futures = misses.stream()
					.map(entry -> CompletableFuture.supplyAsync(() -> Map.entry(
							entry.getKey(),
							loadMissingGeocode(entry.getKey(), entry.getValue())
					), executor))
					.toList();
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
			Map<String, GeocodeBatchResult> results = new LinkedHashMap<>();
			for (CompletableFuture<Map.Entry<String, GeocodeBatchResult>> future : futures) {
				Map.Entry<String, GeocodeBatchResult> entry = future.join();
				results.put(entry.getKey(), entry.getValue());
			}
			return results;
		} finally {
			executor.shutdown();
		}
	}

	private GeocodeBatchResult loadMissingGeocode(String cacheKey, String address) {
		try {
			CachedCoordinates coordinates = singleFlightService.loadAfterMiss(
					"geocode",
					cacheKey,
					CachedCoordinates.class,
					singleFlightLockTtl,
					singleFlightWaitTimeout,
					singleFlightPollInterval,
					() -> requestGeocode(address),
					cachedCoordinates -> redisCache.put(
							"geocode",
							cacheKey,
							cachedCoordinates,
							cachedCoordinates.found() ? geocodeSuccessTtl : geocodeFailureTtl
					)
			);
			return GeocodeBatchResult.from(coordinates);
		} catch (RuntimeException exception) {
			return GeocodeBatchResult.failure(exception);
		}
	}

	private CachedCoordinates requestGeocode(String address) {
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
			return CachedCoordinates.miss();
		}

		NaverAddress addressResult = response.addresses().getFirst();
		Coordinates coordinates = new Coordinates(
				Double.parseDouble(addressResult.y()),
				Double.parseDouble(addressResult.x())
		);
		log.info("Naver maps geocode completed. address={} latitude={} longitude={}",
				address, coordinates.latitude(), coordinates.longitude());
		return CachedCoordinates.hit(coordinates);
	}

	public Optional<String> reverseGeocode(double latitude, double longitude) {
		String rawKey = bucket(latitude) + ":" + bucket(longitude);
		String cacheKey = "swimpulse:cache:reverse-geocode:v1:" + rawKey;
		log.debug("Naver maps reverse geocode cache lookup. cache=reverse-geocode rawKey={} cacheKey={}", rawKey, cacheKey);
		return singleFlightService.getOrLoad(
				"reverse-geocode",
				cacheKey,
				CachedAddress.class,
				singleFlightLockTtl,
				singleFlightWaitTimeout,
				singleFlightPollInterval,
				() -> requestReverseGeocode(latitude, longitude),
				cachedAddress -> redisCache.put(
						"reverse-geocode",
						cacheKey,
						cachedAddress,
						cachedAddress.found() ? reverseGeocodeSuccessTtl : reverseGeocodeFailureTtl
				)
		).toAddress();
	}

	private CachedAddress requestReverseGeocode(double latitude, double longitude) {
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
			return CachedAddress.miss();
		}

		for (NaverReverseResult result : response.results()) {
			String address = formatReverseGeocodedAddress(result);
			if (hasText(address)) {
				log.info("Naver maps reverse geocode completed. latitude={} longitude={} address={}",
						latitude, longitude, address);
				return CachedAddress.hit(address);
			}
		}
		log.info("Naver maps reverse geocode returned no address. latitude={} longitude={}", latitude, longitude);
		return CachedAddress.miss();
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

	private String geocodeCacheKey(String normalizedAddress) {
		return "swimpulse:cache:geocode:v1:" + redisCache.hash(normalizedAddress);
	}

	private String bucket(double value) {
		return String.format(Locale.ROOT, "%.4f", value);
	}

	public record Coordinates(double latitude, double longitude) {
	}

	public record GeocodeBatchResult(Optional<Coordinates> coordinates, RuntimeException exception) {
		public static GeocodeBatchResult hit(Coordinates coordinates) {
			return new GeocodeBatchResult(Optional.of(coordinates), null);
		}

		public static GeocodeBatchResult miss() {
			return new GeocodeBatchResult(Optional.empty(), null);
		}

		public static GeocodeBatchResult failure(RuntimeException exception) {
			return new GeocodeBatchResult(Optional.empty(), exception);
		}

		private static GeocodeBatchResult from(CachedCoordinates cachedCoordinates) {
			return cachedCoordinates.toCoordinates()
					.map(GeocodeBatchResult::hit)
					.orElseGet(GeocodeBatchResult::miss);
		}
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
