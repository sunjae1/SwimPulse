package com.swimpulse.notice;

public record NoticeSourceReverificationResult(
		Long poolId,
		String poolName,
		int checkedSources,
		int verifiedSources,
		int inactiveSources,
		int failedSources,
		boolean homepageDiscoveryRan,
		String message
) {
}
