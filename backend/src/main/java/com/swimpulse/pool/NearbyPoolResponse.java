package com.swimpulse.pool;

public record NearbyPoolResponse(
		PoolResponse pool,
		Double distanceMeters
) {
	public static NearbyPoolResponse of(Pool pool, Double distanceMeters) {
		return new NearbyPoolResponse(PoolResponse.from(pool), distanceMeters);
	}
}
