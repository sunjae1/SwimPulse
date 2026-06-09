package com.swimpulse.location;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import com.swimpulse.pool.PoolSearchNormalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "swimpulse.loadtest.enabled", havingValue = "true")
public class LocationMatchLoadTestService {
	private static final int REQUIRED_CANDIDATE_COUNT = 10;
	private static final String IMPOSSIBLE_NORMALIZED_VALUE = "\u0000";

	private final PoolRepository poolRepository;
	private volatile List<CandidateFixture> fixtures;

	public LocationMatchLoadTestService(PoolRepository poolRepository) {
		this.poolRepository = poolRepository;
	}

	public LocationMatchLoadTestResponse run(String strategy) {
		List<CandidateFixture> candidates = fixtures();
		long startedAt = System.nanoTime();
		int matchedCount = switch (strategy) {
			case "legacy" -> runLegacy(candidates);
			case "batch" -> runBatch(candidates);
			default -> throw new BadRequestException("strategy must be legacy or batch");
		};
		long elapsedMicros = (System.nanoTime() - startedAt) / 1_000;
		return new LocationMatchLoadTestResponse(strategy, candidates.size(), matchedCount, elapsedMicros);
	}

	private int runLegacy(List<CandidateFixture> candidates) {
		List<Pool> allPools = poolRepository.findAll();
		int matchedCount = 0;
		for (CandidateFixture candidate : candidates) {
			String normalizedName = PoolSearchNormalizer.normalize(candidate.name());
			String normalizedRoadAddress = PoolSearchNormalizer.normalize(candidate.roadAddress());
			String normalizedLotAddress = PoolSearchNormalizer.normalize(candidate.lotAddress());
			for (Pool pool : allPools) {
				if (matches(normalizedName, PoolSearchNormalizer.normalize(pool.getName()))
						|| matches(normalizedRoadAddress, PoolSearchNormalizer.normalize(pool.getRoadNameAddress()))
						|| matches(normalizedLotAddress, PoolSearchNormalizer.normalize(pool.getLotNumberAddress()))) {
					matchedCount++;
					break;
				}
			}
		}
		return matchedCount;
	}

	private int runBatch(List<CandidateFixture> candidates) {
		List<NormalizedCandidate> normalizedCandidates = candidates.stream()
				.map(candidate -> new NormalizedCandidate(
						PoolSearchNormalizer.normalize(candidate.name()),
						PoolSearchNormalizer.normalize(candidate.roadAddress()),
						PoolSearchNormalizer.normalize(candidate.lotAddress())
				))
				.toList();
		List<Pool> matchedPools = poolRepository.findMatchingCandidates(
				queryValues(normalizedCandidates, NormalizedCandidate::normalizedName),
				queryValues(normalizedCandidates, NormalizedCandidate::normalizedRoadAddress),
				queryValues(normalizedCandidates, NormalizedCandidate::normalizedLotAddress)
		);
		Map<String, Pool> poolsByName = indexBy(matchedPools, Pool::getNormalizedName);
		Map<String, Pool> poolsByRoadAddress = indexBy(matchedPools, Pool::getNormalizedRoadNameAddress);
		Map<String, Pool> poolsByLotAddress = indexBy(matchedPools, Pool::getNormalizedLotNumberAddress);
		return (int) normalizedCandidates.stream()
				.filter(candidate -> findMatch(candidate, poolsByName, poolsByRoadAddress, poolsByLotAddress) != null)
				.count();
	}

	private Pool findMatch(
			NormalizedCandidate candidate,
			Map<String, Pool> poolsByName,
			Map<String, Pool> poolsByRoadAddress,
			Map<String, Pool> poolsByLotAddress
	) {
		Pool match = find(poolsByName, candidate.normalizedName());
		if (match != null) {
			return match;
		}
		match = find(poolsByRoadAddress, candidate.normalizedRoadAddress());
		if (match != null) {
			return match;
		}
		return find(poolsByLotAddress, candidate.normalizedLotAddress());
	}

	private Pool find(Map<String, Pool> pools, String key) {
		return key == null || key.isBlank() ? null : pools.get(key);
	}

	private Set<String> queryValues(
			List<NormalizedCandidate> candidates,
			Function<NormalizedCandidate, String> extractor
	) {
		Set<String> values = candidates.stream()
				.map(extractor)
				.filter(value -> value != null && !value.isBlank())
				.collect(Collectors.toSet());
		return values.isEmpty() ? Set.of(IMPOSSIBLE_NORMALIZED_VALUE) : values;
	}

	private Map<String, Pool> indexBy(List<Pool> pools, Function<Pool, String> keyExtractor) {
		return pools.stream()
				.filter(pool -> keyExtractor.apply(pool) != null && !keyExtractor.apply(pool).isBlank())
				.collect(Collectors.toMap(
						keyExtractor,
						pool -> pool,
						(existing, duplicate) -> existing,
						LinkedHashMap::new
				));
	}

	private boolean matches(String candidateValue, String poolValue) {
		return candidateValue != null && !candidateValue.isBlank() && candidateValue.equals(poolValue);
	}

	private List<CandidateFixture> fixtures() {
		List<CandidateFixture> current = fixtures;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (fixtures == null) {
				List<Pool> pools = poolRepository.findTop10ByOrderByIdAsc();
				if (pools.size() != REQUIRED_CANDIDATE_COUNT) {
					throw new IllegalStateException("Location match load test requires at least 10 pools.");
				}
				fixtures = pools.stream()
						.map(pool -> new CandidateFixture(
								pool.getName(),
								pool.getRoadNameAddress(),
								pool.getLotNumberAddress()
						))
						.toList();
			}
			return fixtures;
		}
	}

	private record CandidateFixture(String name, String roadAddress, String lotAddress) {
	}

	private record NormalizedCandidate(
			String normalizedName,
			String normalizedRoadAddress,
			String normalizedLotAddress
	) {
	}
}
