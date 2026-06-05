package com.swimpulse.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.swimpulse.pool.NaverMapsGeocodingClient;
import com.swimpulse.pool.PoolRepository;
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
		locationService = new LocationService(naverLocalSearchClient, naverMapsGeocodingClient, poolRepository);
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
}
