package com.swimpulse.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swimpulse.pool.NaverMapsGeocodingClient;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolNearbyQueryRepository;
import com.swimpulse.pool.PoolRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LocationServiceTests {
	@Mock
	private NaverLocalSearchClient naverLocalSearchClient;

	@Mock
	private NaverMapsGeocodingClient naverMapsGeocodingClient;

	@Mock
	private PoolRepository poolRepository;

	@Mock
	private PoolNearbyQueryRepository poolNearbyQueryRepository;

	private LocationService locationService;

	@BeforeEach
	void setUp() {
		locationService = new LocationService(
				naverLocalSearchClient,
				naverMapsGeocodingClient,
				poolRepository,
				poolNearbyQueryRepository
		);
	}

	@Test
	void reverseGeocodeReturnsAddressForCoordinates() {
		when(naverMapsGeocodingClient.isConfigured()).thenReturn(true);
		when(naverMapsGeocodingClient.reverseGeocode(37.5682, 126.9977))
				.thenReturn(Optional.of("서울특별시 중구 을지로 170"));

		GeocodedLocationResponse response = locationService.reverseGeocode(37.5682, 126.9977);

		assertEquals("서울특별시 중구 을지로 170", response.address());
		assertEquals(37.5682, response.latitude());
		assertEquals(126.9977, response.longitude());
	}

	@Test
	void searchUsesNormalizedBatchLookupAndMapsExistingPoolWithoutGeocoding() {
		LocationSearchCandidate candidate = LocationSearchCandidate.basic(
				"성동구립 용답체육센터 수영장",
				"스포츠,오락",
				"서울특별시 성동구 용답동 182-4",
				"서울특별시 성동구 천호대로78길 15-48",
				"https://sports.example.com"
		);
		Pool matchedPool = org.mockito.Mockito.mock(Pool.class);
		when(matchedPool.getId()).thenReturn(3L);
		when(matchedPool.getNormalizedName()).thenReturn("성동구립용답체육센터");
		when(matchedPool.getNormalizedRoadNameAddress()).thenReturn("서울특별시성동구천호대로78길15-48");
		when(matchedPool.getNormalizedLotNumberAddress()).thenReturn("서울특별시성동구용답동182-4");
		when(matchedPool.getLatitude()).thenReturn(37.5618304);
		when(matchedPool.getLongitude()).thenReturn(127.057059);
		when(naverLocalSearchClient.search("서울 수영장", 10)).thenReturn(List.of(candidate));
		when(poolRepository.findMatchingCandidates(
				argThat(values -> values.contains("성동구립용답체육센터")),
				argThat(values -> values.contains("서울특별시성동구천호대로78길15-48")),
				argThat(values -> values.contains("서울특별시성동구용답동182-4"))
		)).thenReturn(List.of(matchedPool));

		List<LocationSearchCandidate> results = locationService.search("서울 수영장", 10, null, null);

		assertEquals(1, results.size());
		assertTrue(results.getFirst().alreadyExists());
		assertEquals(3L, results.getFirst().matchedPoolId());
		assertEquals(37.5618304, results.getFirst().latitude());
		assertEquals(127.057059, results.getFirst().longitude());
		verify(poolRepository, never()).findAll();
		verify(naverMapsGeocodingClient, never()).geocode(org.mockito.ArgumentMatchers.anyString());
	}
}
