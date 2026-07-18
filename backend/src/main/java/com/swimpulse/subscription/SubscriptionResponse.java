package com.swimpulse.subscription;

import com.swimpulse.event.EventResponse;
import com.swimpulse.pool.PoolResponse;
import java.time.Instant;

public record SubscriptionResponse(
		Long id,
		Long userId,
		PoolResponse pool,
		EventResponse event,
		SubscriptionReviewStatus reviewStatus,
		Instant reviewRequestedAt,
		Instant reviewedAt,
		String reviewReason,
		Instant createdAt
) {
	public SubscriptionResponse(Long id, Long userId, PoolResponse pool, EventResponse event, Instant createdAt) {
		this(id, userId, pool, event, SubscriptionReviewStatus.ACTIVE, null, null, null, createdAt);
	}

	public static SubscriptionResponse from(Subscription subscription) {
		return new SubscriptionResponse(
				subscription.getId(),
				subscription.getUser().getId(),
				PoolResponse.from(subscription.getPool()),
				subscription.getEvent() == null ? null : EventResponse.from(subscription.getEvent()),
				subscription.getReviewStatus(),
				subscription.getReviewRequestedAt(),
				subscription.getReviewedAt(),
				subscription.getReviewReason(),
				subscription.getCreatedAt()
		);
	}
}
