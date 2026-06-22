package com.swimpulse.event;

import java.time.Instant;

public record LoadTestSchedulerSeedResponse(
		Long eventId,
		Long poolId,
		String title,
		Instant registrationStartsAt,
		Instant registrationEndsAt,
		int requestedUsers,
		long subscriptionCount,
		int subscriptionsCreated,
		int subscriptionsReused,
		int devicesRegistered
) {
}
