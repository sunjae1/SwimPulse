package com.swimpulse.pool;

import java.util.List;

public record HomepageEnrichmentResponse(
		int processed,
		int updated,
		int autoUpdated,
		int verified,
		int unchanged,
		int needsReview,
		int failed,
		List<HomepageEnrichmentResult> results
) {
	public static HomepageEnrichmentResponse from(List<HomepageEnrichmentResult> results) {
		int updated = (int) results.stream().filter(result -> result.status() == HomepageEnrichmentStatus.UPDATED).count();
		int autoUpdated = (int) results.stream().filter(result -> result.status() == HomepageEnrichmentStatus.AUTO_UPDATED).count();
		int verified = (int) results.stream().filter(result -> result.status() == HomepageEnrichmentStatus.VERIFIED).count();
		int unchanged = (int) results.stream().filter(result -> result.status() == HomepageEnrichmentStatus.UNCHANGED).count();
		int needsReview = (int) results.stream().filter(result -> result.status() == HomepageEnrichmentStatus.NEEDS_REVIEW).count();
		int failed = (int) results.stream().filter(result -> result.status() == HomepageEnrichmentStatus.FAILED).count();
		return new HomepageEnrichmentResponse(results.size(), updated, autoUpdated, verified, unchanged, needsReview, failed, results);
	}
}
