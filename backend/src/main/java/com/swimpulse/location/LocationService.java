package com.swimpulse.location;

import com.swimpulse.common.BadRequestException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.swimpulse.pool.NaverMapsGeocodingClient;
import com.swimpulse.pool.NearbyPoolMatchRow;
import com.swimpulse.pool.NearbySearchOrigin;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolNearbyQueryRepository;
import com.swimpulse.pool.PoolRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import com.swimpulse.pool.PoolSearchNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

@Service
public class LocationService {
	private static final Logger log = LoggerFactory.getLogger(LocationService.class);
	private static final int DEFAULT_DISPLAY = 5;
	private static final int MAX_DISPLAY = 10;
	private static final double DUPLICATE_DISTANCE_METERS = 80;
	private static final String IMPOSSIBLE_NORMALIZED_VALUE = "\u0000";

	private final NaverLocalSearchClient naverLocalSearchClient;
	private final NaverMapsGeocodingClient naverMapsGeocodingClient;
	private final PoolRepository poolRepository;
	private final PoolNearbyQueryRepository poolNearbyQueryRepository;
	private final MeterRegistry meterRegistry;

	public LocationService(
			NaverLocalSearchClient naverLocalSearchClient,
			NaverMapsGeocodingClient naverMapsGeocodingClient,
			PoolRepository poolRepository,
			PoolNearbyQueryRepository poolNearbyQueryRepository,
			MeterRegistry meterRegistry
	) {
		this.naverLocalSearchClient = naverLocalSearchClient;
		this.naverMapsGeocodingClient = naverMapsGeocodingClient;
		this.poolRepository = poolRepository;
		this.poolNearbyQueryRepository = poolNearbyQueryRepository;
		this.meterRegistry = meterRegistry;
	}

	public List<LocationSearchCandidate> search(String query, Integer display, Double latitude, Double longitude) {
		String normalizedQuery = normalizeRequired(query, "query");
		validateOptionalCoordinates(latitude, longitude);
		int normalizedDisplay = normalizeDisplay(display);
		log.info("Location search started. query={} display={} hasOrigin={}",
				normalizedQuery, normalizedDisplay, latitude != null && longitude != null);
		List<NormalizedCandidate> normalizedCandidates = recordStep("naver_local_search", () ->
				naverLocalSearchClient.search(normalizedQuery, normalizedDisplay)
						.stream()
						.map(this::normalizeCandidate)
						.toList()
		);
		PoolMatchLookup matchLookup = recordStep("exact_match_lookup", () -> loadExactMatches(normalizedCandidates));
		List<PreparedCandidate> preparedCandidates = recordStep("prepare_candidates", () ->
				normalizedCandidates.stream()
						.map(candidate -> prepareCandidate(candidate, matchLookup))
						.toList()
		);
		Map<Integer, Pool> coordinateMatches = recordStep("coordinate_match_lookup", () -> loadCoordinateMatches(preparedCandidates));
		List<LocationSearchCandidate> candidates = recordStep("enrich_sort", () ->
				IntStream.range(0, preparedCandidates.size())
						.mapToObj(index -> enrich(preparedCandidates.get(index), coordinateMatches.get(index), latitude, longitude))
						.sorted(compareByDistanceWhenAvailable())
						.toList()
		);
		meterRegistry.summary("swimpulse.location.search.result_count").record(candidates.size());
		meterRegistry.summary("swimpulse.location.search.candidate_count").record(normalizedCandidates.size());
		log.info("Location search completed. query={} resultCount={} exactMatchPoolCount={}",
				normalizedQuery, candidates.size(), matchLookup.poolCount());
		return candidates;
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
			throw new BadRequestException("Naver Maps reverse geocoding request failed: "
					+ exception.getStatusCode().value() + " " + exception.getStatusText());
		}
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

	private LocationSearchCandidate enrich(
			PreparedCandidate preparedCandidate,
			Pool coordinateMatch,
			Double originLatitude,
			Double originLongitude
	) {
		LocationSearchCandidate candidate = preparedCandidate.normalizedCandidate().candidate();
		Pool matchedPool = preparedCandidate.exactMatch() == null ? coordinateMatch : preparedCandidate.exactMatch();
		Double latitude = preparedCandidate.latitude();
		Double longitude = preparedCandidate.longitude();
		Double distance = null;
		if (originLatitude != null && originLongitude != null && latitude != null && longitude != null) {
			distance = distanceMeters(originLatitude, originLongitude, latitude, longitude);
		}
		return candidate.withEnrichment(latitude, longitude, matchedPool != null, matchedPool == null ? null : matchedPool.getId(), distance);
	}

	private PreparedCandidate prepareCandidate(NormalizedCandidate normalizedCandidate, PoolMatchLookup matchLookup) {
		LocationSearchCandidate candidate = normalizedCandidate.candidate();
		Pool exactMatch = matchLookup.find(normalizedCandidate);
		Double latitude = exactMatch == null ? null : exactMatch.getLatitude();
		Double longitude = exactMatch == null ? null : exactMatch.getLongitude();
		String resolutionSource = exactMatch == null ? "unresolved" : "exact_match";
		String address = resolveAddress(candidate);
		if ((latitude == null || longitude == null) && hasText(address) && naverMapsGeocodingClient.isConfigured()) {
			try {
				NaverMapsGeocodingClient.Coordinates coordinates = geocodeCandidateAddress(address).orElse(null);
				if (coordinates != null) {
					latitude = coordinates.latitude();
					longitude = coordinates.longitude();
					resolutionSource = "candidate_geocode";
				} else if (exactMatch != null) {
					resolutionSource = "exact_match";
				}
			} catch (RestClientResponseException exception) {
				log.warn("Candidate geocode failed. title={} status={} {}",
						candidate.title(), exception.getStatusCode().value(), exception.getStatusText());
				latitude = null;
				longitude = null;
				resolutionSource = exactMatch != null ? "exact_match" : "unresolved";
			}
		}
		recordCandidateResolution(resolutionSource);
		return new PreparedCandidate(normalizedCandidate, exactMatch, latitude, longitude);
	}

	private java.util.Optional<NaverMapsGeocodingClient.Coordinates> geocodeCandidateAddress(String address) {
		Timer.Sample sample = Timer.start(meterRegistry);
		String result = "error";
		try {
			java.util.Optional<NaverMapsGeocodingClient.Coordinates> coordinates = naverMapsGeocodingClient.geocode(address);
			result = coordinates.isPresent() ? "hit" : "miss";
			return coordinates;
		} finally {
			sample.stop(Timer.builder("swimpulse.location.search.candidate_geocode")
					.description("Location search candidate geocode latency")
					.tag("result", result)
					.register(meterRegistry));
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
				null,
				normalizeComparable(title),
				normalizeComparable(roadAddress),
				normalizeComparable(address)
		);
		Pool exactMatch = loadExactMatches(List.of(candidate)).find(candidate);
		if (exactMatch != null) {
			return exactMatch;
		}
		return latitude == null || longitude == null ? null : findCoordinateMatch(latitude, longitude);
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

	private Comparator<LocationSearchCandidate> compareByDistanceWhenAvailable() {
		return Comparator
				.comparing((LocationSearchCandidate candidate) -> candidate.distanceMeters() == null ? 1 : 0)
				.thenComparing(candidate -> candidate.distanceMeters() == null ? Double.MAX_VALUE : candidate.distanceMeters());
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

	private String resolveAddress(LocationSearchCandidate candidate) {
		if (hasText(candidate.roadAddress())) {
			return candidate.roadAddress();
		}
		return candidate.address();
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public String normalizeComparable(String value) {
		return PoolSearchNormalizer.normalize(value);
	}

	private NormalizedCandidate normalizeCandidate(LocationSearchCandidate candidate) {
		return new NormalizedCandidate(
				candidate,
				normalizeComparable(candidate.title()),
				normalizeComparable(candidate.roadAddress()),
				normalizeComparable(candidate.address())
		);
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

	private Pool findCoordinateMatch(double latitude, double longitude) {
		return poolRepository.findNearestWithinDistance(latitude, longitude, DUPLICATE_DISTANCE_METERS)
				.orElse(null);
	}

	private Map<Integer, Pool> loadCoordinateMatches(List<PreparedCandidate> candidates) {
		List<NearbySearchOrigin> origins = IntStream.range(0, candidates.size())
				.filter(index -> candidates.get(index).exactMatch() == null)
				.filter(index -> candidates.get(index).latitude() != null && candidates.get(index).longitude() != null)
				.mapToObj(index -> new NearbySearchOrigin(
						index,
						candidates.get(index).latitude(),
						candidates.get(index).longitude()
				))
				.toList();
		if (origins.isEmpty()) {
			return Map.of();
		}
		List<NearbyPoolMatchRow> matchRows = poolNearbyQueryRepository.findNearestMatches(
				origins,
				DUPLICATE_DISTANCE_METERS
		);
		if (matchRows.isEmpty()) {
			return Map.of();
		}
		Map<Long, Pool> poolsById = poolRepository.findAllById(
						matchRows.stream().map(NearbyPoolMatchRow::poolId).collect(Collectors.toSet())
				).stream()
				.collect(Collectors.toMap(Pool::getId, pool -> pool));
		return matchRows.stream()
				.filter(row -> poolsById.containsKey(row.poolId()))
				.collect(Collectors.toMap(
						NearbyPoolMatchRow::candidateIndex,
						row -> poolsById.get(row.poolId())
				));
	}

	private record NormalizedCandidate(
			LocationSearchCandidate candidate,
			String normalizedName,
			String normalizedRoadAddress,
			String normalizedLotAddress
	) {
	}

	private record PreparedCandidate(
			NormalizedCandidate normalizedCandidate,
			Pool exactMatch,
			Double latitude,
			Double longitude
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

	private void recordCandidateResolution(String source) {
		Counter.builder("swimpulse.location.search.candidate_resolution")
				.description("Location search candidate resolution source")
				.tag("source", source)
				.register(meterRegistry)
				.increment();
	}
}
