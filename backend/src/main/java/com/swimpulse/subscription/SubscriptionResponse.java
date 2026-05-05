package com.swimpulse.subscription;

import com.swimpulse.pool.PoolResponse;
import java.time.Instant;

public record SubscriptionResponse(
		Long id,
		Long userId,
		PoolResponse pool,
		Instant createdAt
) {
	public static SubscriptionResponse from(Subscription subscription) {
		return new SubscriptionResponse(
				subscription.getId(),
				subscription.getUser().getId(),
				PoolResponse.from(subscription.getPool()),
				subscription.getCreatedAt()
		);
	}
}
