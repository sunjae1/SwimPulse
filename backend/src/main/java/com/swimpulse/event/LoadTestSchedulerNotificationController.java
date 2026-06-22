package com.swimpulse.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/loadtest/scheduler-notifications")
@ConditionalOnProperty(name = "swimpulse.loadtest.enabled", havingValue = "true")
public class LoadTestSchedulerNotificationController {
	private final LoadTestSchedulerNotificationService service;

	public LoadTestSchedulerNotificationController(LoadTestSchedulerNotificationService service) {
		this.service = service;
	}

	@PostMapping("/seed")
	public LoadTestSchedulerSeedResponse seed(
			@RequestParam(defaultValue = "100") int users,
			@RequestParam(defaultValue = "1") Long poolId,
			@RequestParam(defaultValue = "k6 scheduler due notification") String title,
			@RequestParam(defaultValue = "-5") long startOffsetSeconds,
			@RequestParam(defaultValue = "60") long durationMinutes,
			@RequestParam(defaultValue = "true") boolean registerDevices
	) {
		return service.seed(users, poolId, title, startOffsetSeconds, durationMinutes, registerDevices);
	}

	@PostMapping("/tick")
	public LoadTestSchedulerStatusResponse tick(@RequestParam Long eventId) {
		return service.tick(eventId);
	}

	@GetMapping("/status")
	public LoadTestSchedulerStatusResponse status(@RequestParam Long eventId) {
		return service.status(eventId);
	}
}
