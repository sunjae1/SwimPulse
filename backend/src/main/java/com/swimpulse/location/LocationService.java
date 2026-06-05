package com.swimpulse.location;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.pool.NaverMapsGeocodingClient;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import java.util.List;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

@Service
public class LocationService {
	private static final Logger log = LoggerFactory.getLogger(LocationService.class);
	private static final int DEFAULT_DISPLAY = 5;
	private static final int MAX_DISPLAY = 10;

	private final NaverLocalSearchClient naverLocalSearchClient;
	private final NaverMapsGeocodingClient naverMapsGeocodingClient;
	private final PoolRepository poolRepository;

	public LocationService(
			NaverLocalSearchClient naverLocalSearchClient,
			NaverMapsGeocodingClient naverMapsGeocodingClient,
			PoolRepository poolRepository
	) {
		this.naverLocalSearchClient = naverLocalSearchClient;
		this.naverMapsGeocodingClient = naverMapsGeocodingClient;
		this.poolRepository = poolRepository;
	}

	public List<LocationSearchCandidate> search(String query, Integer display, Double latitude, Double longitude) {
		String normalizedQuery = normalizeRequired(query, "query");
		validateOptionalCoordinates(latitude, longitude);
		int normalizedDisplay = normalizeDisplay(display);
		log.info("Location search started. query={} display={} hasOrigin={}",
				normalizedQuery, normalizedDisplay, latitude != null && longitude != null);
		List<Pool> pools = poolRepository.findAll();
		List<LocationSearchCandidate> candidates = naverLocalSearchClient.search(normalizedQuery, normalizedDisplay)
				.stream()
				.map(candidate -> enrich(candidate, pools, latitude, longitude))
				.sorted(compareByDistanceWhenAvailable())
				.toList();
		log.info("Location search completed. query={} resultCount={}", normalizedQuery, candidates.size());
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
			LocationSearchCandidate candidate,
			List<Pool> pools,
			Double originLatitude,
			Double originLongitude
	) {
		String address = resolveAddress(candidate);
		Double latitude = null;
		Double longitude = null;
		if (hasText(address) && naverMapsGeocodingClient.isConfigured()) {
			try {
				NaverMapsGeocodingClient.Coordinates coordinates = naverMapsGeocodingClient.geocode(address).orElse(null);
				if (coordinates != null) {
					latitude = coordinates.latitude();
					longitude = coordinates.longitude();
				}
			} catch (RestClientResponseException exception) {
				log.warn("Candidate geocode failed. title={} status={} {}",
						candidate.title(), exception.getStatusCode().value(), exception.getStatusText());
				latitude = null;
				longitude = null;
			}
		}

		Pool matchedPool = findMatchingPool(candidate.title(), candidate.roadAddress(), candidate.address(), latitude, longitude, pools);
		Double distance = null;
		if (originLatitude != null && originLongitude != null && latitude != null && longitude != null) {
			distance = distanceMeters(originLatitude, originLongitude, latitude, longitude);
		}
		return candidate.withEnrichment(latitude, longitude, matchedPool != null, matchedPool == null ? null : matchedPool.getId(), distance);
	}

	public Pool findMatchingPool(
			String title,
			String roadAddress,
			String address,
			Double latitude,
			Double longitude,
			List<Pool> pools
	) {
		String normalizedTitle = normalizeComparable(title);
		String normalizedRoadAddress = normalizeComparable(roadAddress);
		String normalizedAddress = normalizeComparable(address);
		for (Pool pool : pools) {
			if (hasText(normalizedTitle) && normalizeComparable(pool.getName()).equals(normalizedTitle)) {
				return pool;
			}
			if (hasText(normalizedRoadAddress) && normalizeComparable(pool.getRoadNameAddress()).equals(normalizedRoadAddress)) {
				return pool;
			}
			if (hasText(normalizedAddress) && normalizeComparable(pool.getLotNumberAddress()).equals(normalizedAddress)) {
				return pool;
			}
			if (latitude != null && longitude != null && pool.getLatitude() != null && pool.getLongitude() != null
					&& distanceMeters(latitude, longitude, pool.getLatitude(), pool.getLongitude()) <= 80) {
				return pool;
			}
		}
		return null;
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
		if (value == null) {
			return "";
		}
		return value.replaceAll("<[^>]*>", "")
				.replaceAll("\\s+", "")
				.replace("수영장", "")
				.toLowerCase();
	}
}
