package com.swimpulse.pool;

public record PoolImageEnrichmentResult(
		Long poolId,
		String poolName,
		PoolImageEnrichmentStatus status,
		String imageUrl,
		String previousImageUrl,
		String source,
		String reason
) {
}
