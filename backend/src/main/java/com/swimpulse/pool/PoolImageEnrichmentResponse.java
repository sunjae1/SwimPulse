package com.swimpulse.pool;

import java.util.List;

public record PoolImageEnrichmentResponse(
		int processedPools,
		int updated,
		int unchanged,
		int notFound,
		int skipped,
		int failed,
		List<PoolImageEnrichmentResult> results
) {
	public static PoolImageEnrichmentResponse from(List<PoolImageEnrichmentResult> results) {
		return new PoolImageEnrichmentResponse(
				results.size(),
				count(results, PoolImageEnrichmentStatus.UPDATED),
				count(results, PoolImageEnrichmentStatus.UNCHANGED),
				count(results, PoolImageEnrichmentStatus.NOT_FOUND),
				count(results, PoolImageEnrichmentStatus.SKIPPED),
				count(results, PoolImageEnrichmentStatus.FAILED),
				results
		);
	}

	private static int count(List<PoolImageEnrichmentResult> results, PoolImageEnrichmentStatus status) {
		return (int) results.stream()
				.filter(result -> result.status() == status)
				.count();
	}
}
