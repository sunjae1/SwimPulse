package com.swimpulse.pool;

public record GeocodePoolResult(
		Long poolId,
		String poolName,
		String address,
		GeocodeStatus status,
		Double latitude,
		Double longitude,
		String message
) {
	public static GeocodePoolResult success(Pool pool) {
		return new GeocodePoolResult(
				pool.getId(),
				pool.getName(),
				pool.resolveGeocodeAddress(),
				pool.getGeocodeStatus(),
				pool.getLatitude(),
				pool.getLongitude(),
				"Geocoded"
		);
	}

	public static GeocodePoolResult failed(Pool pool, String message) {
		return new GeocodePoolResult(
				pool.getId(),
				pool.getName(),
				pool.resolveGeocodeAddress(),
				pool.getGeocodeStatus(),
				pool.getLatitude(),
				pool.getLongitude(),
				message
		);
	}
}
