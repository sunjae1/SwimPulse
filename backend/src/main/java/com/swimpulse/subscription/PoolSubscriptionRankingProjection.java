package com.swimpulse.subscription;

public interface PoolSubscriptionRankingProjection {
	Long getPoolId();

	String getPoolName();

	Long getSubscriptionCount();
}
