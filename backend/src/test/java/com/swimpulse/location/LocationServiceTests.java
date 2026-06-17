package com.swimpulse.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swimpulse.pool.NaverMapsGeocodingClient;
import com.swimpulse.pool.PoolRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

	private LocationService locationService;

	@BeforeEach
	void setUp() {
		locationService = new LocationService(
				naverLocalSearchClient,
				naverMapsGeocodingClient,
				poolRepository,
				new SimpleMeterRegistry()
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
	void searchReturnsLocalCandidatesWithoutDbMatchingOrGeocoding() {
		LocationSearchCandidate candidate = LocationSearchCandidate.basic(
				"성동구립 용답체육센터 수영장",
				"스포츠,오락",
				"서울특별시 성동구 용답동 182-4",
				"서울특별시 성동구 천호대로78길 15-48",
				"https://sports.example.com"
		);
		when(naverLocalSearchClient.search("서울 수영장", 10)).thenReturn(List.of(candidate));

		List<LocationSearchCandidate> results = locationService.search("서울 수영장", 10, null, null);

		assertEquals(1, results.size());
		assertEquals("성동구립 용답체육센터 수영장", results.getFirst().title());
		verify(poolRepository, never()).findMatchingCandidates(
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any()
		);
		verify(naverMapsGeocodingClient, never()).geocode(org.mockito.ArgumentMatchers.anyString());
	}
}
