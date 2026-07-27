package com.swimpulse.location;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.TooManyRequestsException;
import com.swimpulse.pool.NaverMapsGeocodingClient;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import com.swimpulse.pool.PoolSearchNormalizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

@Service
public class LocationService {
	private static final Logger log = LoggerFactory.getLogger(LocationService.class);
	private static final int DEFAULT_DISPLAY = 5;
	private static final int MAX_DISPLAY = 10;
	private static final int SEARCH_GEOCODE_CONCURRENCY = 5;
	private static final String IMPOSSIBLE_NORMALIZED_VALUE = "\u0000";

	private final NaverLocalSearchClient naverLocalSearchClient;
	private final NaverMapsGeocodingClient naverMapsGeocodingClient;
	private final PoolRepository poolRepository;
	private final MeterRegistry meterRegistry;

	public LocationService(
			NaverLocalSearchClient naverLocalSearchClient,
			NaverMapsGeocodingClient naverMapsGeocodingClient,
			PoolRepository poolRepository,
			MeterRegistry meterRegistry
	) {
		this.naverLocalSearchClient = naverLocalSearchClient;
		this.naverMapsGeocodingClient = naverMapsGeocodingClient;
		this.poolRepository = poolRepository;
		this.meterRegistry = meterRegistry;
	}

	public List<LocationSearchCandidate> search(String query, Integer display, Double latitude, Double longitude) {
		String normalizedQuery = normalizeRequired(query, "query");
		validateOptionalCoordinates(latitude, longitude);
		int normalizedDisplay = normalizeDisplay(display);
		log.info("Location search started. query={} display={} hasOrigin={}",
				normalizedQuery, normalizedDisplay, latitude != null && longitude != null);
		List<LocationSearchCandidate> candidates = recordStep("naver_local_search", () ->
				naverLocalSearchClient.search(normalizedQuery, normalizedDisplay)
		);
		List<LocationSearchCandidate> resolvedCandidates = enrichMissingCoordinates(candidates);
		meterRegistry.summary("swimpulse.location.search.result_count").record(resolvedCandidates.size());
		log.info("Location search completed. query={} resultCount={} selectableCount={}",
				normalizedQuery,
				resolvedCandidates.size(),
				resolvedCandidates.stream().filter(this::hasCoordinates).count());
		return resolvedCandidates;
	}

	private List<LocationSearchCandidate> enrichMissingCoordinates(List<LocationSearchCandidate> candidates) {
		if (candidates.isEmpty() || candidates.stream().allMatch(this::hasCoordinates)
				|| !naverMapsGeocodingClient.isConfigured()) {
			return candidates;
		}

		List<String> roadAddresses = candidates.stream()
				.filter(candidate -> !hasCoordinates(candidate))
				.map(LocationSearchCandidate::roadAddress)
				.filter(this::hasText)
				.distinct()
				.toList();
		Map<String, NaverMapsGeocodingClient.GeocodeBatchResult> roadResults =
				naverMapsGeocodingClient.geocodeAll(roadAddresses, SEARCH_GEOCODE_CONCURRENCY);

		List<LocationSearchCandidate> roadResolved = candidates.stream()
				.map(candidate -> enrichCandidate(
						candidate,
						geocodeResult(roadResults, candidate.roadAddress())
				))
				.toList();

		List<String> lotAddresses = roadResolved.stream()
				.filter(candidate -> !hasCoordinates(candidate))
				.filter(candidate -> hasText(candidate.address()))
				.filter(candidate -> !candidate.address().equals(candidate.roadAddress()))
				.map(LocationSearchCandidate::address)
				.distinct()
				.toList();
		Map<String, NaverMapsGeocodingClient.GeocodeBatchResult> lotResults =
				naverMapsGeocodingClient.geocodeAll(lotAddresses, SEARCH_GEOCODE_CONCURRENCY);

		return roadResolved.stream()
				.map(candidate -> enrichCandidate(
						candidate,
						geocodeResult(lotResults, candidate.address())
				))
				.toList();
	}

	private NaverMapsGeocodingClient.GeocodeBatchResult geocodeResult(
			Map<String, NaverMapsGeocodingClient.GeocodeBatchResult> results,
			String address
	) {
		return hasText(address) ? results.get(address) : null;
	}

	private LocationSearchCandidate enrichCandidate(
			LocationSearchCandidate candidate,
			NaverMapsGeocodingClient.GeocodeBatchResult result
	) {
		if (hasCoordinates(candidate) || result == null || result.exception() != null) {
			return candidate;
		}
		return result.coordinates()
				.map(coordinates -> candidate.withEnrichment(
						coordinates.latitude(),
						coordinates.longitude(),
						candidate.distanceMeters()
				))
				.orElse(candidate);
	}

	private boolean hasCoordinates(LocationSearchCandidate candidate) {
		return candidate.latitude() != null
				&& candidate.longitude() != null
				&& Double.isFinite(candidate.latitude())
				&& Double.isFinite(candidate.longitude())
				&& Math.abs(candidate.latitude()) <= 90
				&& Math.abs(candidate.longitude()) <= 180;
	}

	public GeocodedLocationResponse geocode(String address) {
		String normalizedAddress = normalizeRequired(address, "address");
		if (!naverMapsGeocodingClient.isConfigured()) {
			throw new BadRequestException("Naver Maps geocoding credentials are not configured.");
		}

		try {
			GeocodedLocationResponse response = naverMapsGeocodingClient.geocode(normalizedAddress)
					.map(coordinates -> new GeocodedLocationResponse(
							normalizedAddress,
							coordinates.latitude(),
							coordinates.longitude()
					))
					.orElseThrow(() -> new BadRequestException("No coordinates found for address."));
			log.info("Address geocoded. address={} latitude={} longitude={}",
					normalizedAddress, response.latitude(), response.longitude());
			return response;
		} catch (RestClientResponseException exception) {
			throwIfRateLimited("Naver Maps geocoding request failed", exception);
			throw new BadRequestException("Naver Maps geocoding request failed: "
					+ exception.getStatusCode().value() + " " + exception.getStatusText());
		}
	}

	public GeocodedLocationResponse reverseGeocode(Double latitude, Double longitude) {
		validateRequiredCoordinates(latitude, longitude);
		if (!naverMapsGeocodingClient.isConfigured()) {
			throw new BadRequestException("Naver Maps geocoding credentials are not configured.");
		}

		try {
			String address = naverMapsGeocodingClient.reverseGeocode(latitude, longitude)
					.orElseThrow(() -> new BadRequestException("No address found for coordinates."));
			log.info("Coordinates reverse geocoded. latitude={} longitude={} address={}", latitude, longitude, address);
			return new GeocodedLocationResponse(address, latitude, longitude);
		} catch (RestClientResponseException exception) {
			throwIfRateLimited("Naver Maps reverse geocoding request failed", exception);
			throw new BadRequestException("Naver Maps reverse geocoding request failed: "
					+ exception.getStatusCode().value() + " " + exception.getStatusText());
		}
	}

	public Pool findMatchingPool(
			String title,
			String roadAddress,
			String address,
			Double latitude,
			Double longitude
	) {
		NormalizedCandidate candidate = new NormalizedCandidate(
				normalizeComparable(title),
				normalizeComparable(roadAddress),
				normalizeComparable(address)
		);
		return loadExactMatches(List.of(candidate)).find(candidate);
	}

	public double distanceMeters(double latitude1, double longitude1, double latitude2, double longitude2) {
		double earthRadiusMeters = 6_371_000;
		double deltaLatitude = Math.toRadians(latitude2 - latitude1);
		double deltaLongitude = Math.toRadians(longitude2 - longitude1);
		double a = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
				+ Math.cos(Math.toRadians(latitude1)) * Math.cos(Math.toRadians(latitude2))
				* Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);
		double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
		return earthRadiusMeters * c;
	}

	public String normalizeComparable(String value) {
		return PoolSearchNormalizer.normalize(value);
	}

	private String normalizeRequired(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new BadRequestException(fieldName + " is required");
		}
		return value.trim();
	}

	private int normalizeDisplay(Integer display) {
		if (display == null) {
			return DEFAULT_DISPLAY;
		}
		if (display < 1 || display > MAX_DISPLAY) {
			throw new BadRequestException("display must be between 1 and 10");
		}
		return display;
	}

	private void validateOptionalCoordinates(Double latitude, Double longitude) {
		if (latitude == null && longitude == null) {
			return;
		}
		validateRequiredCoordinates(latitude, longitude);
	}

	private void validateRequiredCoordinates(Double latitude, Double longitude) {
		if (latitude == null || Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
			throw new BadRequestException("latitude must be between -90 and 90");
		}
		if (longitude == null || Double.isNaN(longitude) || longitude < -180 || longitude > 180) {
			throw new BadRequestException("longitude must be between -180 and 180");
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private void throwIfRateLimited(String prefix, RestClientResponseException exception) {
		if (exception.getStatusCode().value() == 429) {
			throw new TooManyRequestsException(prefix + ": 429 Too Many Requests");
		}
	}

	private PoolMatchLookup loadExactMatches(List<NormalizedCandidate> candidates) {
		if (candidates.isEmpty()) {
			return PoolMatchLookup.empty();
		}
		Set<String> normalizedNames = normalizedValues(candidates, NormalizedCandidate::normalizedName);
		Set<String> normalizedRoadAddresses = normalizedValues(candidates, NormalizedCandidate::normalizedRoadAddress);
		Set<String> normalizedLotAddresses = normalizedValues(candidates, NormalizedCandidate::normalizedLotAddress);
		List<Pool> matchedPools = poolRepository.findMatchingCandidates(
				nonEmptyQueryValues(normalizedNames),
				nonEmptyQueryValues(normalizedRoadAddresses),
				nonEmptyQueryValues(normalizedLotAddresses)
		);
		return PoolMatchLookup.from(matchedPools);
	}

	private Set<String> normalizedValues(
			List<NormalizedCandidate> candidates,
			Function<NormalizedCandidate, String> extractor
	) {
		return candidates.stream()
				.map(extractor)
				.filter(this::hasText)
				.collect(Collectors.toSet());
	}

	private Set<String> nonEmptyQueryValues(Set<String> values) {
		return values.isEmpty() ? Set.of(IMPOSSIBLE_NORMALIZED_VALUE) : values;
	}

	private record NormalizedCandidate(
			String normalizedName,
			String normalizedRoadAddress,
			String normalizedLotAddress
	) {
	}

	private record PoolMatchLookup(
			Map<String, Pool> byName,
			Map<String, Pool> byRoadAddress,
			Map<String, Pool> byLotAddress,
			int poolCount
	) {
		private static PoolMatchLookup empty() {
			return new PoolMatchLookup(Map.of(), Map.of(), Map.of(), 0);
		}

		private static PoolMatchLookup from(List<Pool> pools) {
			return new PoolMatchLookup(
					indexBy(pools, Pool::getNormalizedName),
					indexBy(pools, Pool::getNormalizedRoadNameAddress),
					indexBy(pools, Pool::getNormalizedLotNumberAddress),
					pools.size()
			);
		}

		private Pool find(NormalizedCandidate candidate) {
			Pool match = find(byName, candidate.normalizedName());
			if (match != null) {
				return match;
			}
			match = find(byRoadAddress, candidate.normalizedRoadAddress());
			if (match != null) {
				return match;
			}
			return find(byLotAddress, candidate.normalizedLotAddress());
		}

		private static Pool find(Map<String, Pool> pools, String key) {
			return key == null || key.isBlank() ? null : pools.get(key);
		}

		private static Map<String, Pool> indexBy(List<Pool> pools, Function<Pool, String> keyExtractor) {
			return pools.stream()
					.filter(pool -> keyExtractor.apply(pool) != null && !keyExtractor.apply(pool).isBlank())
					.collect(Collectors.toMap(
							keyExtractor,
							pool -> pool,
							(existing, duplicate) -> existing,
							LinkedHashMap::new
					));
		}
	}

	private <T> T recordStep(String step, Supplier<T> supplier) {
		Timer.Sample sample = Timer.start(meterRegistry);
		try {
			return supplier.get();
		} finally {
			sample.stop(Timer.builder("swimpulse.location.search.step")
					.description("Location search internal step latency")
					.tag("step", step)
					.register(meterRegistry));
		}
	}
}
