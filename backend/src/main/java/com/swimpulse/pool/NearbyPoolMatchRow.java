package com.swimpulse.pool;

public record NearbyPoolMatchRow(
		int candidateIndex,
		Long poolId,
		Double distanceMeters
) {
}
