package com.swimpulse.event;

import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {
	private static final Logger log = LoggerFactory.getLogger(EventController.class);

	private final EventService eventService;

	public EventController(EventService eventService) {
		this.eventService = eventService;
	}

	@GetMapping
	public List<EventResponse> findEvents(
			@RequestParam(required = false) EventStatus status,
			@RequestParam(required = false) Long poolId
	) {
		log.info("Registration events requested. status={} poolId={}", status, poolId);
		return eventService.findEvents(status, poolId);
	}

	@PostMapping
	public EventResponse createEvent(@Valid @RequestBody CreateEventRequest request) {
		log.info("Registration event creation requested. poolId={} title={}", request.poolId(), request.title());
		return eventService.createEvent(request);
	}

	@PatchMapping("/{eventId}/status")
	public EventResponse updateStatus(@PathVariable Long eventId, @Valid @RequestBody UpdateEventStatusRequest request) {
		log.info("Registration event status update requested. eventId={} status={}", eventId, request.status());
		return eventService.updateStatus(eventId, request.status());
	}
}
