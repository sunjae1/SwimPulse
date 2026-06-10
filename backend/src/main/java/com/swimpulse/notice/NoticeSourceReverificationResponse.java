package com.swimpulse.notice;

import java.util.List;

public record NoticeSourceReverificationResponse(
		int processedPools,
		int checkedSources,
		int verifiedSources,
		int inactiveSources,
		int failedSources,
		List<NoticeSourceReverificationResult> results
) {
	public static NoticeSourceReverificationResponse from(List<NoticeSourceReverificationResult> results) {
		return new NoticeSourceReverificationResponse(
				results.size(),
				results.stream().mapToInt(NoticeSourceReverificationResult::checkedSources).sum(),
				results.stream().mapToInt(NoticeSourceReverificationResult::verifiedSources).sum(),
				results.stream().mapToInt(NoticeSourceReverificationResult::inactiveSources).sum(),
				results.stream().mapToInt(NoticeSourceReverificationResult::failedSources).sum(),
				results
		);
	}
}
