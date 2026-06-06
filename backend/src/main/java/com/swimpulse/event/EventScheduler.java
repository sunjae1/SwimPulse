package com.swimpulse.event;

import com.swimpulse.common.RedisLockService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EventScheduler {
	private static final Logger log = LoggerFactory.getLogger(EventScheduler.class);

	private final EventService eventService;
	private final RedisLockService redisLockService;
	private final String schedulerLockKey;
	private final Duration schedulerLockTtl;

	public EventScheduler(
			EventService eventService,
			RedisLockService redisLockService,
			@Value("${swimpulse.event.scheduler-lock-key:swimpulse:locks:event-scheduler}") String schedulerLockKey,
			@Value("${swimpulse.event.scheduler-lock-ttl-ms:25000}") long schedulerLockTtlMs
	) {
		this.eventService = eventService;
		this.redisLockService = redisLockService;
		this.schedulerLockKey = schedulerLockKey;
		this.schedulerLockTtl = Duration.ofMillis(schedulerLockTtlMs);
	}

	@Scheduled(fixedDelayString = "${swimpulse.event.scheduler-delay-ms:30000}")
	public void tick() {
		RedisLockService.LockToken lockToken = redisLockService.acquire(schedulerLockKey, schedulerLockTtl).orElse(null);
		if (lockToken == null) {
			log.debug("Event scheduler tick skipped because another backend instance holds the lock. lockKey={}", schedulerLockKey);
			return;
		}
		try {
			EventService.EventStatusRefreshResult refreshResult = eventService.refreshStatuses();
			EventService.DueNotificationQueueResult queueResult = eventService.queueDueRegistrationNotifications();
			log.info(
					"Event scheduler tick completed. checkedEvents={} changedEvents={} activeEvents={} reminderEvents={} openEvents={} queuedNotifications={}",
					refreshResult.checkedEvents(),
					refreshResult.changedEvents(),
					queueResult.activeEvents(),
					queueResult.reminderEvents(),
					queueResult.openEvents(),
					queueResult.notificationsCreated()
			);
		} finally {
			redisLockService.release(lockToken);
		}
	}
}
