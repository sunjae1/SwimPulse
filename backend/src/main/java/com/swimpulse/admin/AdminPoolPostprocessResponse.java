package com.swimpulse.admin;

import com.swimpulse.notice.NoticeScanResponse;
import com.swimpulse.pool.HomepageEnrichmentResult;
import com.swimpulse.pool.PoolAddRequestResponse;
import com.swimpulse.pool.PoolImageEnrichmentResult;

public record AdminPoolPostprocessResponse(
		PoolAddRequestResponse request,
		HomepageEnrichmentResult homepage,
		PoolImageEnrichmentResult image,
		NoticeScanResponse notices
) {
}
