package com.swimpulse.pool;

import java.util.List;

public record HomepageEnrichmentResponse(
		int processed,
		int updated,
		int needsReview,
		int failed,
		List<HomepageEnrichmentResult> results
) {
	public static HomepageEnrichmentResponse from(List<HomepageEnrichmentResult> results) {
		int updated = (int) results.stream().filter(result -> result.status() == HomepageEnrichmentStatus.UPDATED).count();
		int needsReview = (int) results.stream().filter(result -> result.status() == HomepageEnrichmentStatus.NEEDS_REVIEW).count();
		int failed = (int) results.stream().filter(result -> result.status() == HomepageEnrichmentStatus.FAILED).count();
		return new HomepageEnrichmentResponse(results.size(), updated, needsReview, failed, results);
	}
}
