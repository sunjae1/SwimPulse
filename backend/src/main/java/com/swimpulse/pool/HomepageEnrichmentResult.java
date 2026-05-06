package com.swimpulse.pool;

public record HomepageEnrichmentResult(
		Long poolId,
		String poolName,
		HomepageEnrichmentStatus status,
		String homepageUrl,
		String candidateTitle,
		String candidateAddress,
		String candidateHomepageUrl,
		String message
) {
}
