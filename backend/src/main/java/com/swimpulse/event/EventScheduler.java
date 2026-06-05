package com.swimpulse.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EventScheduler {
	private static final Logger log = LoggerFactory.getLogger(EventScheduler.class);

	private final EventService eventService;

	public EventScheduler(EventService eventService) {
		this.eventService = eventService;
	}

	@Scheduled(fixedDelayString = "${swimpulse.event.scheduler-delay-ms:30000}")
	public void tick() {
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
	}
}
