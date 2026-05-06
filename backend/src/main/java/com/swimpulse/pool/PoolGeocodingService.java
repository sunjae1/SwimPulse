package com.swimpulse.pool;

import com.swimpulse.common.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

@Service
public class PoolGeocodingService {
	private final PoolRepository poolRepository;
	private final NaverMapsGeocodingClient naverMapsGeocodingClient;

	public PoolGeocodingService(PoolRepository poolRepository, NaverMapsGeocodingClient naverMapsGeocodingClient) {
		this.poolRepository = poolRepository;
		this.naverMapsGeocodingClient = naverMapsGeocodingClient;
	}

	@Transactional
	public GeocodeBatchResponse geocodePendingPools() {
		if (!naverMapsGeocodingClient.isConfigured()) {
			throw new BadRequestException("NAVER_MAPS_CLIENT_ID or NAVER_MAPS_CLIENT_SECRET is not configured.");
		}

		List<Pool> pools = poolRepository.findTop50ByGeocodeStatusOrderByIdAsc(GeocodeStatus.PENDING);
		List<GeocodePoolResult> results = new ArrayList<>();

		for (Pool pool : pools) {
			results.add(geocode(pool));
		}

		return GeocodeBatchResponse.from(results);
	}

	private GeocodePoolResult geocode(Pool pool) {
		String address = pool.resolveGeocodeAddress();
		if (address == null || address.isBlank()) {
			pool.markGeocodeFailed();
			return GeocodePoolResult.failed(pool, "Address is empty.");
		}

		try {
			return naverMapsGeocodingClient.geocode(address)
					.map(coordinates -> {
						pool.markGeocodeSuccess(coordinates.latitude(), coordinates.longitude());
						return GeocodePoolResult.success(pool);
					})
					.orElseGet(() -> {
						pool.markGeocodeFailed();
						return GeocodePoolResult.failed(pool, "No coordinates found.");
					});
		}
		catch (RestClientResponseException exception) {
			throw new BadRequestException("Naver Maps geocoding request failed: " + exception.getStatusCode());
		}
		catch (NumberFormatException exception) {
			pool.markGeocodeFailed();
			return GeocodePoolResult.failed(pool, "Invalid coordinate format: " + exception.getMessage());
		}
		catch (RuntimeException exception) {
			throw new BadRequestException("Naver Maps geocoding response could not be processed: " + exception.getMessage());
		}
	}
}
