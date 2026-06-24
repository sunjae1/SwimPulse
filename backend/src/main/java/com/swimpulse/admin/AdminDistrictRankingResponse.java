package com.swimpulse.admin;

import com.swimpulse.subscription.DistrictSubscriptionRankingProjection;

public record AdminDistrictRankingResponse(
		String district,
		long subscriptionCount
) {
	public static AdminDistrictRankingResponse from(DistrictSubscriptionRankingProjection projection) {
		return new AdminDistrictRankingResponse(
				projection.getDistrict(),
				projection.getSubscriptionCount() == null ? 0 : projection.getSubscriptionCount()
		);
	}
}
