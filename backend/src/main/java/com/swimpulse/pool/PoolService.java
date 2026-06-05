package com.swimpulse.pool;

import com.swimpulse.common.NotFoundException;
import com.swimpulse.common.BadRequestException;
import com.swimpulse.location.LocationSearchCandidate;
import com.swimpulse.location.LocationService;
import com.swimpulse.location.NaverLocalSearchClient;
import com.swimpulse.pool.NaverMapsGeocodingClient.Coordinates;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

@Service
public class PoolService {
	private static final Logger log = LoggerFactory.getLogger(PoolService.class);

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
		List<PoolResponse> pools = poolRepository.findAllByOrderByNameAsc()
				.stream()
				.map(PoolResponse::from)
				.toList();
		log.info("Pools listed. count={}", pools.size());
		return pools;
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
		log.info("Nearby pools lookup started. latitude={} longitude={} limit={}", latitude, longitude, normalizedLimit);
		List<NearbyPoolRow> rows = poolNearbyQueryRepository.findNearby(latitude, longitude, normalizedLimit);
		Map<Long, Double> distancesByPoolId = new LinkedHashMap<>();
		rows.forEach(row -> distancesByPoolId.put(row.poolId(), row.distanceMeters()));

		Map<Long, Pool> poolsById = poolRepository.findAllById(distancesByPoolId.keySet())
				.stream()
				.collect(LinkedHashMap::new, (map, pool) -> map.put(pool.getId(), pool), LinkedHashMap::putAll);

		List<NearbyPoolResponse> nearbyPools = distancesByPoolId.entrySet()
				.stream()
				.map(entry -> NearbyPoolResponse.of(poolsById.get(entry.getKey()), entry.getValue()))
				.toList();
		log.info("Nearby pools lookup completed. latitude={} longitude={} resultCount={}",
				latitude, longitude, nearbyPools.size());
		return nearbyPools;
	}

	@Transactional
	public PoolResponse createFromLocationCandidate(CreatePoolFromLocationCandidateRequest request) {
		String title = normalizeRequired(request.title(), "title");
		String roadAddress = normalizeBlankToNull(request.roadAddress());
		String address = normalizeBlankToNull(request.address());
		String candidateHomepageUrl = normalizeBlankToNull(request.link());
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
			log.info("Location candidate matched existing pool. poolId={} title={}", existing.getId(), title);
			return PoolResponse.from(existing);
		}

		Pool created = Pool.fromLocationCandidate(
				title,
				resolvedAddress,
				roadAddress,
				address,
				candidateHomepageUrl,
				latitude,
				longitude
		);
		Pool saved = poolRepository.save(created);
		log.info("Pool created from location candidate. poolId={} title={} hasHomepage={}",
				saved.getId(), title, hasText(candidateHomepageUrl));
		return PoolResponse.from(saved);
	}

	@Transactional
	public HomepageEnrichmentResponse enrichHomepages(Integer limit) {
		int normalizedLimit = normalizeLimit(limit == null ? 50 : limit);
		log.info("Homepage enrichment started. limit={}", normalizedLimit);
		List<HomepageEnrichmentResult> results = poolRepository.findAllByOrderByNameAsc()
				.stream()
				.filter(pool -> !hasText(pool.getHomepageUrl()))
				.limit(normalizedLimit)
				.map(this::enrichHomepage)
				.toList();
		log.info("Homepage enrichment completed. limit={} resultCount={} updated={} review={} failed={}",
				normalizedLimit,
				results.size(),
				countByStatus(results, HomepageEnrichmentStatus.UPDATED),
				countByStatus(results, HomepageEnrichmentStatus.NEEDS_REVIEW),
				countByStatus(results, HomepageEnrichmentStatus.FAILED));
		return HomepageEnrichmentResponse.from(results);
	}

	@Transactional
	public HomepageEnrichmentResponse reverifyHomepages(Integer limit) {
		int normalizedLimit = normalizeLimit(limit == null ? 50 : limit);
		log.info("Homepage reverification started. limit={}", normalizedLimit);
		List<HomepageEnrichmentResult> results = poolRepository.findAllByOrderByNameAsc()
				.stream()
				.filter(pool -> hasText(pool.getHomepageUrl()))
				.limit(normalizedLimit)
				.map(this::reverifyHomepage)
				.toList();
		log.info("Homepage reverification completed. limit={} resultCount={} verified={} autoUpdated={} review={} failed={}",
				normalizedLimit,
				results.size(),
				countByStatus(results, HomepageEnrichmentStatus.VERIFIED),
				countByStatus(results, HomepageEnrichmentStatus.AUTO_UPDATED),
				countByStatus(results, HomepageEnrichmentStatus.NEEDS_REVIEW),
				countByStatus(results, HomepageEnrichmentStatus.FAILED));
		return HomepageEnrichmentResponse.from(results);
	}

	private HomepageEnrichmentResult enrichHomepage(Pool pool) {
		try {
			log.info("Homepage enrichment checking pool. poolId={} name={}", pool.getId(), pool.getName());
			List<LocationSearchCandidate> candidates = naverLocalSearchClient.search(pool.getName(), 5);
			LocationSearchCandidate best = findBestHomepageCandidate(pool, candidates);
			if (best == null) {
				log.info("Homepage enrichment needs review. poolId={} candidateCount={}", pool.getId(), candidates.size());
				LocationSearchCandidate firstCandidate = candidates.stream()
						.filter(candidate -> hasText(candidate.link()))
						.findFirst()
						.orElse(null);
				recordCandidate(pool, HomepageVerificationStatus.NEEDS_REVIEW, firstCandidate);
				return new HomepageEnrichmentResult(
						pool.getId(),
						pool.getName(),
						HomepageEnrichmentStatus.NEEDS_REVIEW,
						null,
						pool.getHomepageUrl(),
						firstCandidate == null ? null : firstCandidate.title(),
						firstCandidate == null ? null : resolveCandidateAddress(firstCandidate),
						firstCandidate == null ? null : firstCandidate.link(),
						firstCandidate == null
								? "Homepage candidate not found."
								: "Candidate found but name/address match was not confident enough."
				);
			}
			pool.updateHomepageUrl(
					best.link(),
					HomepageSource.NAVER_LOCAL_SEARCH,
					HomepageVerificationStatus.VERIFIED,
					best.title(),
					resolveCandidateAddress(best),
					best.link()
			);
			log.info("Homepage enrichment updated pool. poolId={} homepageUrl={}", pool.getId(), best.link());
			return new HomepageEnrichmentResult(
					pool.getId(),
					pool.getName(),
					HomepageEnrichmentStatus.UPDATED,
					best.link(),
					null,
					best.title(),
					resolveCandidateAddress(best),
					best.link(),
					"Homepage updated from Naver local search."
			);
		} catch (RuntimeException exception) {
			log.warn("Homepage enrichment failed. poolId={} message={}", pool.getId(), exception.getMessage());
			return new HomepageEnrichmentResult(
					pool.getId(),
					pool.getName(),
					HomepageEnrichmentStatus.FAILED,
					null,
					pool.getHomepageUrl(),
					null,
					null,
					null,
					exception.getMessage()
			);
		}
	}

	private HomepageEnrichmentResult reverifyHomepage(Pool pool) {
		String previousHomepageUrl = pool.getHomepageUrl();
		try {
			log.info("Homepage reverification checking pool. poolId={} name={} currentHomepage={}",
					pool.getId(), pool.getName(), previousHomepageUrl);
			List<LocationSearchCandidate> candidates = naverLocalSearchClient.search(pool.getName(), 5);
			LocationSearchCandidate best = findBestHomepageCandidate(pool, candidates);
			if (best == null) {
				LocationSearchCandidate firstCandidate = candidates.stream()
						.filter(candidate -> hasText(candidate.link()))
						.findFirst()
						.orElse(null);
				recordCandidate(pool, HomepageVerificationStatus.NEEDS_REVIEW, firstCandidate);
				return new HomepageEnrichmentResult(
						pool.getId(),
						pool.getName(),
						HomepageEnrichmentStatus.NEEDS_REVIEW,
						previousHomepageUrl,
						previousHomepageUrl,
						firstCandidate == null ? null : firstCandidate.title(),
						firstCandidate == null ? null : resolveCandidateAddress(firstCandidate),
						firstCandidate == null ? null : firstCandidate.link(),
						firstCandidate == null
								? "No Naver local search homepage candidate found. Current homepage kept."
								: "Candidate found but name/address/category match was not confident enough. Current homepage kept."
				);
			}

			if (sameHomepage(previousHomepageUrl, best.link())) {
				recordCandidate(pool, HomepageVerificationStatus.VERIFIED, best);
				return new HomepageEnrichmentResult(
						pool.getId(),
						pool.getName(),
						HomepageEnrichmentStatus.VERIFIED,
						previousHomepageUrl,
						previousHomepageUrl,
						best.title(),
						resolveCandidateAddress(best),
						best.link(),
						"Current homepage matches the best Naver local search candidate."
				);
			}

			if (shouldAutoReplaceHomepage(previousHomepageUrl, best.link())) {
				pool.updateHomepageUrl(
						best.link(),
						HomepageSource.NAVER_LOCAL_SEARCH,
						HomepageVerificationStatus.AUTO_UPDATED,
						best.title(),
						resolveCandidateAddress(best),
						best.link()
				);
				log.info("Homepage auto-updated. poolId={} previous={} next={}",
						pool.getId(), previousHomepageUrl, best.link());
				return new HomepageEnrichmentResult(
						pool.getId(),
						pool.getName(),
						HomepageEnrichmentStatus.AUTO_UPDATED,
						best.link(),
						previousHomepageUrl,
						best.title(),
						resolveCandidateAddress(best),
						best.link(),
						"Current homepage looked like a broad institution root, so it was replaced with the confident Naver local search candidate."
				);
			}

			recordCandidate(pool, HomepageVerificationStatus.NEEDS_REVIEW, best);
			return new HomepageEnrichmentResult(
					pool.getId(),
					pool.getName(),
					HomepageEnrichmentStatus.NEEDS_REVIEW,
					previousHomepageUrl,
					previousHomepageUrl,
					best.title(),
					resolveCandidateAddress(best),
					best.link(),
					"Confident candidate differs from current homepage, but current homepage was not safe to auto-replace."
			);
		} catch (RuntimeException exception) {
			pool.recordHomepageVerification(
					HomepageSource.NAVER_LOCAL_SEARCH,
					HomepageVerificationStatus.FAILED,
					null,
					null,
					null
			);
			log.warn("Homepage reverification failed. poolId={} message={}", pool.getId(), exception.getMessage());
			return new HomepageEnrichmentResult(
					pool.getId(),
					pool.getName(),
					HomepageEnrichmentStatus.FAILED,
					previousHomepageUrl,
					previousHomepageUrl,
					null,
					null,
					null,
					exception.getMessage()
			);
		}
	}

	private LocationSearchCandidate findBestHomepageCandidate(Pool pool, List<LocationSearchCandidate> candidates) {
		return candidates.stream()
				.filter(candidate -> hasText(candidate.link()))
				.filter(candidate -> !isBroadInstitutionRootUrl(candidate.link()))
				.map(candidate -> new ScoredHomepageCandidate(candidate, scoreHomepageCandidate(pool, candidate)))
				.filter(candidate -> candidate.score() >= 50)
				.max(Comparator
						.comparingInt(ScoredHomepageCandidate::score)
						.thenComparing(candidate -> candidate.candidate().title() == null ? 0 : -candidate.candidate().title().length()))
				.map(ScoredHomepageCandidate::candidate)
				.orElse(null);
	}

	private int scoreHomepageCandidate(Pool pool, LocationSearchCandidate candidate) {
		String title = locationService.normalizeComparable(candidate.title());
		String poolName = locationService.normalizeComparable(pool.getName());
		int score = 0;
		if (hasText(title) && hasText(poolName)) {
			if (title.equals(poolName)) {
				score += 60;
			} else if (title.contains(poolName) || poolName.contains(title)) {
				score += 40;
			}
		}
		String candidateAddress = locationService.normalizeComparable(candidate.roadAddress() != null ? candidate.roadAddress() : candidate.address());
		String poolAddress = locationService.normalizeComparable(pool.resolveGeocodeAddress());
		if (hasText(candidateAddress) && hasText(poolAddress)
				&& (candidateAddress.contains(poolAddress) || poolAddress.contains(candidateAddress))) {
			score += 35;
		}
		String category = locationService.normalizeComparable(candidate.category());
		if (containsAny(category, List.of("체육", "스포츠", "수영", "구민체육센터"))) {
			score += 10;
		}
		if (isBroadInstitutionRootUrl(candidate.link())) {
			score -= 20;
		} else {
			score += 5;
		}
		return score;
	}

	private void recordCandidate(Pool pool, HomepageVerificationStatus status, LocationSearchCandidate candidate) {
		pool.recordHomepageVerification(
				HomepageSource.NAVER_LOCAL_SEARCH,
				status,
				candidate == null ? null : candidate.title(),
				candidate == null ? null : resolveCandidateAddress(candidate),
				candidate == null ? null : candidate.link()
		);
	}

	private boolean shouldAutoReplaceHomepage(String currentHomepageUrl, String candidateHomepageUrl) {
		return isBroadInstitutionRootUrl(currentHomepageUrl) && !isBroadInstitutionRootUrl(candidateHomepageUrl);
	}

	private boolean sameHomepage(String left, String right) {
		return hasText(left) && hasText(right) && normalizeHomepageForComparison(left).equals(normalizeHomepageForComparison(right));
	}

	private String normalizeHomepageForComparison(String value) {
		if (!hasText(value)) {
			return "";
		}
		try {
			URI uri = new URI(value.trim());
			String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase();
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
			String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
			String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
			int port = uri.getPort();
			String portPart = port < 0 ? "" : ":" + port;
			return scheme + "://" + host + portPart + path + query;
		} catch (URISyntaxException exception) {
			return value.trim().replaceAll("/+$", "").toLowerCase();
		}
	}

	private boolean isBroadInstitutionRootUrl(String value) {
		if (!hasText(value)) {
			return false;
		}
		try {
			URI uri = new URI(value.trim());
			String host = uri.getHost();
			String path = uri.getPath();
			if (host == null) {
				return false;
			}
			boolean rootPath = !hasText(path) || "/".equals(path);
			return rootPath && host.toLowerCase().endsWith(".go.kr");
		} catch (URISyntaxException exception) {
			return false;
		}
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

	private long countByStatus(List<HomepageEnrichmentResult> results, HomepageEnrichmentStatus status) {
		return results.stream()
				.filter(result -> result.status() == status)
				.count();
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

	private boolean containsAny(String haystack, List<String> needles) {
		if (!hasText(haystack)) {
			return false;
		}
		return needles.stream().anyMatch(haystack::contains);
	}

	private record ScoredHomepageCandidate(LocationSearchCandidate candidate, int score) {
	}
}
