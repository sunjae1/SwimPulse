package com.swimpulse.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swimpulse.location.LocationService;
import com.swimpulse.location.LocationSearchCandidate;
import com.swimpulse.location.NaverLocalSearchClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PoolServiceTests {
	@Mock
	private PoolRepository poolRepository;

	@Mock
	private PoolNearbyQueryRepository poolNearbyQueryRepository;

	@Mock
	private LocationService locationService;

	@Mock
	private NaverMapsGeocodingClient naverMapsGeocodingClient;

	@Mock
	private NaverLocalSearchClient naverLocalSearchClient;

	private PoolService poolService;

	@BeforeEach
	void setUp() {
		poolService = new PoolService(
				poolRepository,
				poolNearbyQueryRepository,
				locationService,
				naverMapsGeocodingClient,
				naverLocalSearchClient
		);
	}

	@Test
	void createFromLocationCandidateStoresCandidateLinkAsHomepage() {
		when(locationService.findMatchingPool(anyString(), nullable(String.class), nullable(String.class), any(), any()))
				.thenReturn(null);
		when(poolRepository.save(any(Pool.class))).thenAnswer(invocation -> invocation.getArgument(0));

		PoolResponse response = poolService.createFromLocationCandidate(new CreatePoolFromLocationCandidateRequest(
				"소사국민체육센터",
				"경기도 부천시 소사구 소사로 108",
				"경기도 부천시 소사구 소사로 108",
				"https://www.best.or.kr/fmcs/44",
				37.481,
				126.795
		));

		assertEquals("https://www.best.or.kr/fmcs/44", response.homepageUrl());
		assertEquals(HomepageSource.USER_LOCATION_CANDIDATE, response.homepageSource());
		assertEquals(HomepageVerificationStatus.VERIFIED, response.homepageStatus());
		verify(naverLocalSearchClient, never()).search(anyString(), anyInt());
	}

	@Test
	void createFromLocationCandidateDoesNotSearchAgainWhenCandidateLinkIsMissing() {
		when(locationService.findMatchingPool(anyString(), nullable(String.class), nullable(String.class), any(), any()))
				.thenReturn(null);
		when(poolRepository.save(any(Pool.class))).thenAnswer(invocation -> invocation.getArgument(0));

		PoolResponse response = poolService.createFromLocationCandidate(new CreatePoolFromLocationCandidateRequest(
				"홈페이지 없는 체육센터",
				"경기도 부천시 원미구 예시로 1",
				"경기도 부천시 원미구 예시로 1",
				null,
				37.5,
				126.8
		));

		assertNull(response.homepageUrl());
		assertEquals(HomepageVerificationStatus.UNVERIFIED, response.homepageStatus());
		verify(naverLocalSearchClient, never()).search(anyString(), anyInt());
	}

	@Test
	void createFromLocationCandidateReturnsExistingPoolWithoutOverwritingHomepage() {
		Pool existing = Pool.fromLocationCandidate(
				"기존 체육센터",
				"경기도 부천시 원미구 기존로 1",
				"경기도 부천시 원미구 기존로 1",
				null,
				"https://old.example.com",
				37.5,
				126.8
		);
		when(locationService.findMatchingPool(anyString(), nullable(String.class), nullable(String.class), any(), any()))
				.thenReturn(existing);

		PoolResponse response = poolService.createFromLocationCandidate(new CreatePoolFromLocationCandidateRequest(
				"기존 체육센터",
				"경기도 부천시 원미구 기존로 1",
				"경기도 부천시 원미구 기존로 1",
				"https://new.example.com",
				37.5,
				126.8
		));

		assertEquals("https://old.example.com", response.homepageUrl());
		verify(poolRepository, never()).save(any(Pool.class));
		verify(naverLocalSearchClient, never()).search(anyString(), anyInt());
	}

	@Test
	void findLocationCandidatesUsesExactMatchWithoutCoordinateFallback() {
		LocationSearchCandidate candidate = LocationSearchCandidate.basic(
				"부천국민체육센터 수영장",
				"스포츠,오락>수영장",
				"경기도 부천시 원미구 중동 1156",
				"경기도 부천시 원미구 석천로 293",
				"https://pool.example.com"
		);
		Pool matchedPool = org.mockito.Mockito.mock(Pool.class);
		when(matchedPool.getId()).thenReturn(42L);
		when(matchedPool.getNormalizedName()).thenReturn("부천국민체육센터");
		when(matchedPool.getNormalizedRoadNameAddress()).thenReturn("경기도부천시원미구석천로293");
		when(matchedPool.getNormalizedLotNumberAddress()).thenReturn("경기도부천시원미구중동1156");
		when(naverMapsGeocodingClient.isConfigured()).thenReturn(true);
		when(naverMapsGeocodingClient.reverseGeocode(37.5, 126.7))
				.thenReturn(Optional.of("경기도 부천시 원미구 중동"));
		when(naverLocalSearchClient.searchPoolLocationCandidates("경기도 부천시 원미구 수영장", 10)).thenReturn(List.of(candidate));
		when(naverMapsGeocodingClient.geocode("경기도 부천시 원미구 석천로 293"))
				.thenReturn(Optional.of(new NaverMapsGeocodingClient.Coordinates(37.5001, 126.7001)));
		when(poolRepository.findMatchingCandidates(any(), any(), any())).thenReturn(List.of(matchedPool));
		when(locationService.distanceMeters(37.5, 126.7, 37.5001, 126.7001)).thenReturn(15.0);

		List<PoolLocationCandidateResponse> results = poolService.findLocationCandidates(37.5, 126.7, 5000, "수영장", 10);

		assertEquals(1, results.size());
		assertEquals(42L, results.getFirst().matchedPoolId());
		assertEquals(true, results.getFirst().alreadyExists());
		assertEquals(15.0, results.getFirst().distanceMeters());
		verify(poolRepository, never()).findNearestWithinDistance(anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	void reverifyHomepagesAutoUpdatesBroadGovernmentRootHomepage() {
		stubNormalizeComparable();
		Pool pool = Pool.fromLocationCandidate(
				"성동구립 용답체육센터",
				"서울 성동구 천호대로78길 15-48",
				"서울 성동구 천호대로78길 15-48",
				null,
				"https://www.sd.go.kr",
				37.561,
				127.057
		);
		when(poolRepository.findAllByOrderByNameAsc()).thenReturn(List.of(pool));
		when(naverLocalSearchClient.search("성동구립 용답체육센터", 5)).thenReturn(List.of(LocationSearchCandidate.basic(
				"성동구립용답체육센터",
				"스포츠,오락>구민체육센터",
				"서울특별시 성동구 용답동 223-5 서울교통공사 별관 지하1층 성동구립용답체육센터",
				"서울특별시 성동구 천호대로78길 15-48 서울교통공사 별관 지하1층 성동구립용답체육센터",
				"https://sports.happysd.or.kr"
		)));

		HomepageEnrichmentResponse response = poolService.reverifyHomepages(10);

		assertEquals(1, response.autoUpdated());
		HomepageEnrichmentResult result = response.results().getFirst();
		assertEquals(HomepageEnrichmentStatus.AUTO_UPDATED, result.status());
		assertEquals("https://www.sd.go.kr", result.previousHomepageUrl());
		assertEquals("https://sports.happysd.or.kr", result.homepageUrl());
		assertEquals("https://sports.happysd.or.kr", pool.getHomepageUrl());
		assertEquals(HomepageSource.NAVER_LOCAL_SEARCH, pool.getHomepageSource());
		assertEquals(HomepageVerificationStatus.AUTO_UPDATED, pool.getHomepageStatus());
		assertEquals("성동구립용답체육센터", pool.getHomepageCandidateTitle());
	}

	@Test
	void enrichHomepagesDoesNotAutoSaveBroadGovernmentRootCandidate() {
		Pool pool = new Pool(
				"성동구립 용답체육센터",
				"성동구",
				"테스트 시설"
		);
		when(poolRepository.findAllByOrderByNameAsc()).thenReturn(List.of(pool));
		when(naverLocalSearchClient.search("성동구립 용답체육센터", 5)).thenReturn(List.of(LocationSearchCandidate.basic(
				"성동구립용답체육센터",
				"스포츠,오락>구민체육센터",
				"서울특별시 성동구 용답동 223-5",
				"서울특별시 성동구 천호대로78길 15-48",
				"https://www.sd.go.kr"
		)));

		HomepageEnrichmentResponse response = poolService.enrichHomepages(10);

		assertEquals(1, response.needsReview());
		HomepageEnrichmentResult result = response.results().getFirst();
		assertEquals(HomepageEnrichmentStatus.NEEDS_REVIEW, result.status());
		assertNull(pool.getHomepageUrl());
		assertEquals("https://www.sd.go.kr", pool.getHomepageCandidateLink());
		assertEquals(HomepageVerificationStatus.NEEDS_REVIEW, pool.getHomepageStatus());
	}

	@Test
	void reverifyHomepagesKeepsSpecificHomepageWhenCandidateDiffers() {
		stubNormalizeComparable();
		Pool pool = Pool.fromLocationCandidate(
				"소사국민체육센터",
				"경기도 부천시 소사구 소사로 108",
				"경기도 부천시 소사구 소사로 108",
				null,
				"https://www.best.or.kr/fmcs/44",
				37.481,
				126.795
		);
		when(poolRepository.findAllByOrderByNameAsc()).thenReturn(List.of(pool));
		when(naverLocalSearchClient.search("소사국민체육센터", 5)).thenReturn(List.of(LocationSearchCandidate.basic(
				"소사국민체육센터",
				"스포츠,오락>구민체육센터",
				"경기도 부천시 소사구 소사본동 400",
				"경기도 부천시 소사구 소사로 108",
				"https://www.best.or.kr"
		)));

		HomepageEnrichmentResponse response = poolService.reverifyHomepages(10);

		assertEquals(1, response.needsReview());
		HomepageEnrichmentResult result = response.results().getFirst();
		assertEquals(HomepageEnrichmentStatus.NEEDS_REVIEW, result.status());
		assertEquals("https://www.best.or.kr/fmcs/44", result.homepageUrl());
		assertEquals("https://www.best.or.kr/fmcs/44", pool.getHomepageUrl());
		assertEquals(HomepageVerificationStatus.NEEDS_REVIEW, pool.getHomepageStatus());
		assertEquals("https://www.best.or.kr", pool.getHomepageCandidateLink());
	}

	private void stubNormalizeComparable() {
		when(locationService.normalizeComparable(any())).thenAnswer(invocation -> {
			String value = invocation.getArgument(0);
			if (value == null) {
				return "";
			}
			return value.replaceAll("<[^>]*>", "")
					.replaceAll("\\s+", "")
					.replace("수영장", "")
					.toLowerCase();
		});
	}
}
