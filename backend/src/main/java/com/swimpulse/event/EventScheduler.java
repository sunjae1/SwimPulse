package com.swimpulse.event;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EventScheduler {
	private final EventService eventService;

	public EventScheduler(EventService eventService) {
		this.eventService = eventService;
	}

	@Scheduled(fixedDelayString = "${swimpulse.event.scheduler-delay-ms:30000}")
	public void tick() {
		eventService.refreshStatuses();
		eventService.queueDueRegistrationNotifications();
	}
}
