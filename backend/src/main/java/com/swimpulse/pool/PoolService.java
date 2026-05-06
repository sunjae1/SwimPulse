package com.swimpulse.pool;

import com.swimpulse.common.NotFoundException;
import com.swimpulse.common.BadRequestException;
import com.swimpulse.location.LocationSearchCandidate;
import com.swimpulse.location.LocationService;
import com.swimpulse.location.NaverLocalSearchClient;
import com.swimpulse.pool.NaverMapsGeocodingClient.Coordinates;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

@Service
public class PoolService {
	private final PoolRepository poolRepository;
	private final PoolNearbyQueryRepository poolNearbyQueryRepository;
	private final LocationService locationService;
	private final NaverMapsGeocodingClient naverMapsGeocodingClient;
	private final NaverLocalSearchClient naverLocalSearchClient;

	public PoolService(
			PoolRepository poolRepository,
			PoolNearbyQueryRepository poolNearbyQueryRepository,
			LocationService locationService,
			NaverMapsGeocodingClient naverMapsGeocodingClient,
			NaverLocalSearchClient naverLocalSearchClient
	) {
		this.poolRepository = poolRepository;
		this.poolNearbyQueryRepository = poolNearbyQueryRepository;
		this.locationService = locationService;
		this.naverMapsGeocodingClient = naverMapsGeocodingClient;
		this.naverLocalSearchClient = naverLocalSearchClient;
	}

	@Transactional(readOnly = true)
	public List<PoolResponse> findPools() {
		return poolRepository.findAllByOrderByNameAsc()
				.stream()
				.map(PoolResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public PoolResponse findPool(Long poolId) {
		return poolRepository.findById(poolId)
				.map(PoolResponse::from)
				.orElseThrow(() -> new NotFoundException("Pool not found: " + poolId));
	}

	@Transactional(readOnly = true)
	public List<NearbyPoolResponse> findNearbyPools(Double latitude, Double longitude, Integer limit) {
		validateCoordinates(latitude, longitude);
		int normalizedLimit = normalizeLimit(limit);
		List<NearbyPoolRow> rows = poolNearbyQueryRepository.findNearby(latitude, longitude, normalizedLimit);
		Map<Long, Double> distancesByPoolId = new LinkedHashMap<>();
		rows.forEach(row -> distancesByPoolId.put(row.poolId(), row.distanceMeters()));

		Map<Long, Pool> poolsById = poolRepository.findAllById(distancesByPoolId.keySet())
				.stream()
				.collect(LinkedHashMap::new, (map, pool) -> map.put(pool.getId(), pool), LinkedHashMap::putAll);

		return distancesByPoolId.entrySet()
				.stream()
				.map(entry -> NearbyPoolResponse.of(poolsById.get(entry.getKey()), entry.getValue()))
				.toList();
	}

	@Transactional
	public PoolResponse createFromLocationCandidate(CreatePoolFromLocationCandidateRequest request) {
		String title = normalizeRequired(request.title(), "title");
		String roadAddress = normalizeBlankToNull(request.roadAddress());
		String address = normalizeBlankToNull(request.address());
		String resolvedAddress = roadAddress != null ? roadAddress : address;
		if (resolvedAddress == null) {
			throw new BadRequestException("address or roadAddress is required");
		}

		Double latitude = request.latitude();
		Double longitude = request.longitude();
		if (latitude == null || longitude == null) {
			Coordinates coordinates = geocodeRequired(resolvedAddress);
			latitude = coordinates.latitude();
			longitude = coordinates.longitude();
		}
		validateCoordinates(latitude, longitude);

		Pool existing = locationService.findMatchingPool(title, roadAddress, address, latitude, longitude, poolRepository.findAll());
		if (existing != null) {
			return PoolResponse.from(existing);
		}

		Pool created = Pool.fromLocationCandidate(
				title,
				resolvedAddress,
				roadAddress,
				address,
				normalizeBlankToNull(request.link()),
				latitude,
				longitude
		);
		return PoolResponse.from(poolRepository.save(created));
	}

	@Transactional
	public HomepageEnrichmentResponse enrichHomepages(Integer limit) {
		int normalizedLimit = normalizeLimit(limit == null ? 50 : limit);
		List<HomepageEnrichmentResult> results = poolRepository.findAllByOrderByNameAsc()
				.stream()
				.filter(pool -> !hasText(pool.getHomepageUrl()))
				.limit(normalizedLimit)
				.map(this::enrichHomepage)
				.toList();
		return HomepageEnrichmentResponse.from(results);
	}

	private HomepageEnrichmentResult enrichHomepage(Pool pool) {
		try {
			List<LocationSearchCandidate> candidates = naverLocalSearchClient.search(pool.getName(), 5);
			LocationSearchCandidate best = candidates
					.stream()
					.filter(candidate -> hasText(candidate.link()))
					.filter(candidate -> isHomepageCandidateMatch(pool, candidate))
					.min(Comparator.comparingInt(candidate -> candidate.title().length()))
					.orElse(null);
			if (best == null) {
				LocationSearchCandidate firstCandidate = candidates.stream()
						.filter(candidate -> hasText(candidate.link()))
						.findFirst()
						.orElse(null);
				return new HomepageEnrichmentResult(
						pool.getId(),
						pool.getName(),
						HomepageEnrichmentStatus.NEEDS_REVIEW,
						null,
						firstCandidate == null ? null : firstCandidate.title(),
						firstCandidate == null ? null : resolveCandidateAddress(firstCandidate),
						firstCandidate == null ? null : firstCandidate.link(),
						firstCandidate == null
								? "Homepage candidate not found."
								: "Candidate found but name/address match was not confident enough."
				);
			}
			pool.updateHomepageUrl(best.link());
			return new HomepageEnrichmentResult(
					pool.getId(),
					pool.getName(),
					HomepageEnrichmentStatus.UPDATED,
					best.link(),
					best.title(),
					resolveCandidateAddress(best),
					best.link(),
					"Homepage updated from Naver local search."
			);
		} catch (RuntimeException exception) {
			return new HomepageEnrichmentResult(
					pool.getId(),
					pool.getName(),
					HomepageEnrichmentStatus.FAILED,
					null,
					null,
					null,
					null,
					exception.getMessage()
			);
		}
	}

	private boolean isHomepageCandidateMatch(Pool pool, LocationSearchCandidate candidate) {
		String title = locationService.normalizeComparable(candidate.title());
		String poolName = locationService.normalizeComparable(pool.getName());
		if (title.contains(poolName) || poolName.contains(title)) {
			return true;
		}
		String candidateAddress = locationService.normalizeComparable(candidate.roadAddress() != null ? candidate.roadAddress() : candidate.address());
		String poolAddress = locationService.normalizeComparable(pool.resolveGeocodeAddress());
		return hasText(candidateAddress) && hasText(poolAddress) && (candidateAddress.contains(poolAddress) || poolAddress.contains(candidateAddress));
	}

	private String resolveCandidateAddress(LocationSearchCandidate candidate) {
		return candidate.roadAddress() != null ? candidate.roadAddress() : candidate.address();
	}

	private void validateCoordinates(Double latitude, Double longitude) {
		if (latitude == null || Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
			throw new BadRequestException("latitude must be between -90 and 90");
		}
		if (longitude == null || Double.isNaN(longitude) || longitude < -180 || longitude > 180) {
			throw new BadRequestException("longitude must be between -180 and 180");
		}
	}

	private int normalizeLimit(Integer limit) {
		if (limit == null) {
			return 10;
		}
		if (limit < 1 || limit > 50) {
			throw new BadRequestException("limit must be between 1 and 50");
		}
		return limit;
	}

	private Coordinates geocodeRequired(String address) {
		if (!naverMapsGeocodingClient.isConfigured()) {
			throw new BadRequestException("Naver Maps geocoding credentials are not configured.");
		}
		try {
			return naverMapsGeocodingClient.geocode(address)
					.orElseThrow(() -> new BadRequestException("No coordinates found for address."));
		} catch (RestClientResponseException exception) {
			throw new BadRequestException("Naver Maps geocoding request failed: "
					+ exception.getStatusCode().value() + " " + exception.getStatusText());
		}
	}

	private String normalizeRequired(String value, String fieldName) {
		if (!hasText(value)) {
			throw new BadRequestException(fieldName + " is required");
		}
		return value.trim();
	}

	private String normalizeBlankToNull(String value) {
		if (!hasText(value)) {
			return null;
		}
		return value.trim();
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
