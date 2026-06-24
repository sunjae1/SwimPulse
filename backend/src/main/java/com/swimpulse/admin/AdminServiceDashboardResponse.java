package com.swimpulse.admin;

import com.swimpulse.pool.PoolAddRequestResponse;
import java.time.Instant;
import java.util.List;

public record AdminServiceDashboardResponse(
		Instant generatedAt,
		AdminDashboardResponse.AdminOverview overview,
		AdminDashboardResponse.AdminNoticeDashboard notices,
		List<AdminPoolRankingResponse> topSubscribedPools,
		List<AdminDistrictRankingResponse> topSubscribedDistricts,
		List<PoolAddRequestResponse> pendingPoolAddRequests,
		List<PoolAddRequestResponse> poolAddRequests
) {
}
