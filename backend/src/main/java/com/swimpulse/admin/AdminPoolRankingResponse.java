package com.swimpulse.admin;

import com.swimpulse.subscription.PoolSubscriptionRankingProjection;

public record AdminPoolRankingResponse(
		Long poolId,
		String poolName,
		long subscriptionCount
) {
	public static AdminPoolRankingResponse from(PoolSubscriptionRankingProjection projection) {
		return new AdminPoolRankingResponse(
				projection.getPoolId(),
				projection.getPoolName(),
				projection.getSubscriptionCount() == null ? 0 : projection.getSubscriptionCount()
		);
	}
}
