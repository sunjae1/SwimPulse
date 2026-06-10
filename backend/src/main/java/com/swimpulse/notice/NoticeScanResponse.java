package com.swimpulse.notice;

import java.util.List;

public record NoticeScanResponse(
		Long poolId,
		String poolName,
		String homepageUrl,
		int scannedLinks,
		List<PoolNoticeResponse> notices,
		String message,
		List<String> trace,
		boolean sharedResult,
		boolean waitedForActiveScan,
		boolean latestCheckFailed
) {
}
