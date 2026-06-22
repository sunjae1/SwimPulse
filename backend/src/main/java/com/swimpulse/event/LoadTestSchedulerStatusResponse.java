package com.swimpulse.event;

public record LoadTestSchedulerStatusResponse(
		Long eventId,
		EventStatus eventStatus,
		boolean reminderQueued,
		boolean startQueued,
		long subscriptionCount,
		long notificationCount,
		long queuedCount,
		long sendingCount,
		long sentCount,
		long failedCount,
		long redisQueueLength
) {
}
