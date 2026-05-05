package com.swimpulse.event;

import jakarta.validation.Valid;
import java.util.List;
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
	private final EventService eventService;

	public EventController(EventService eventService) {
		this.eventService = eventService;
	}

	@GetMapping
	public List<EventResponse> findEvents(
			@RequestParam(required = false) EventStatus status,
			@RequestParam(required = false) Long poolId
	) {
		return eventService.findEvents(status, poolId);
	}

	@PostMapping
	public EventResponse createEvent(@Valid @RequestBody CreateEventRequest request) {
		return eventService.createEvent(request);
	}

	@PatchMapping("/{eventId}/status")
	public EventResponse updateStatus(@PathVariable Long eventId, @Valid @RequestBody UpdateEventStatusRequest request) {
		return eventService.updateStatus(eventId, request.status());
	}
}
