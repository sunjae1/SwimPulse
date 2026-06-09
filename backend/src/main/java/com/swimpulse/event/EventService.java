package com.swimpulse.event;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.NotFoundException;
import com.swimpulse.notification.NotificationService;
import com.swimpulse.notification.NotificationType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
	private static final Logger log = LoggerFactory.getLogger(EventService.class);

	private final RegistrationEventRepository eventRepository;
	private final RegistrationEventResolver eventResolver;
	private final NotificationService notificationService;
	private final long reminderMinutes;

	public EventService(
			RegistrationEventRepository eventRepository,
			RegistrationEventResolver eventResolver,
			NotificationService notificationService,
			@Value("${swimpulse.notification.reminder-minutes:10}") long reminderMinutes
	) {
		this.eventRepository = eventRepository;
		this.eventResolver = eventResolver;
		this.notificationService = notificationService;
		this.reminderMinutes = reminderMinutes;
	}

	@Transactional(readOnly = true)
	public List<EventResponse> findEvents(EventStatus status, Long poolId) {
		if (poolId != null) {
			return eventRepository.findByPool_IdOrderByRegistrationStartsAtAsc(poolId)
					.stream()
					.filter(event -> status == null || event.getStatus() == status)
					.map(EventResponse::from)
					.toList();
		}
		if (status != null) {
			return eventRepository.findByStatusOrderByRegistrationStartsAtAsc(status)
					.stream()
					.map(EventResponse::from)
					.toList();
		}
		return eventRepository.findAllByOrderByRegistrationStartsAtAsc()
				.stream()
				.map(EventResponse::from)
				.toList();
	}

	@Transactional
	public EventResponse createEvent(CreateEventRequest request) {
		if (!request.registrationStartsAt().isBefore(request.registrationEndsAt())) {
			throw new BadRequestException("registrationStartsAt must be before registrationEndsAt");
		}
		String title = normalizeTitle(request.title());
		RegistrationEvent event = eventResolver.getOrCreate(
				request.poolId(),
				title,
				request.registrationStartsAt(),
				request.registrationEndsAt()
		);
		log.info("Registration event resolved. eventId={} poolId={} startsAt={} endsAt={}",
				event.getId(), request.poolId(), event.getRegistrationStartsAt(), event.getRegistrationEndsAt());
		return EventResponse.from(event);
	}

	@Transactional
	public EventResponse updateStatus(Long eventId, EventStatus status) {
		RegistrationEvent event = getEvent(eventId);
		event.changeStatus(status);
		log.info("Registration event status updated. eventId={} status={}", eventId, status);
		return EventResponse.from(event);
	}

	@Transactional
	public EventStatusRefreshResult refreshStatuses() {
		Instant now = Instant.now();
		List<RegistrationEvent> events = eventRepository.findAll();
		int changed = 0;
		for (RegistrationEvent event : events) {
			EventStatus before = event.getStatus();
			event.changeStatus(calculateStatus(
					event.getRegistrationStartsAt(),
					event.getRegistrationEndsAt(),
					now
			));
			if (before != event.getStatus()) {
				changed++;
			}
		}
		if (changed > 0) {
			log.info("Registration event statuses refreshed. total={} changed={}", events.size(), changed);
		} else {
			log.debug("Registration event statuses checked. total={} changed=0", events.size());
		}
		return new EventStatusRefreshResult(events.size(), changed);
	}

	@Transactional
	public DueNotificationQueueResult queueDueRegistrationNotifications() {
		Instant now = Instant.now();
		Instant reminderThreshold = now.plus(reminderMinutes, ChronoUnit.MINUTES);
		List<RegistrationEvent> activeEvents = eventRepository.findByStatusInOrderByRegistrationStartsAtAsc(
				List.of(EventStatus.UPCOMING, EventStatus.OPEN)
		);
		int reminderEvents = 0;
		int openEvents = 0;
		int notificationsCreated = 0;

		for (RegistrationEvent event : activeEvents) {
			if (!event.isReminderQueued()
					&& event.getRegistrationStartsAt().isAfter(now)
					&& !event.getRegistrationStartsAt().isAfter(reminderThreshold)) {
				notificationsCreated += notificationService.createAndQueueForEvent(event, NotificationType.REGISTRATION_REMINDER);
				event.markReminderQueued();
				reminderEvents++;
				log.info("Reminder notifications queued. eventId={} poolId={}", event.getId(), event.getPool().getId());
			}

			if (!event.isStartQueued() && !event.getRegistrationStartsAt().isAfter(now)) {
				notificationsCreated += notificationService.createAndQueueForEvent(event, NotificationType.REGISTRATION_OPEN);
				event.markStartQueued();
				openEvents++;
				log.info("Registration-open notifications queued. eventId={} poolId={}", event.getId(), event.getPool().getId());
			}
		}
		return new DueNotificationQueueResult(activeEvents.size(), reminderEvents, openEvents, notificationsCreated);
	}

	private RegistrationEvent getEvent(Long eventId) {
		return eventRepository.findById(eventId)
				.orElseThrow(() -> new NotFoundException("Event not found: " + eventId));
	}

	private EventStatus calculateStatus(Instant startsAt, Instant endsAt, Instant now) {
		if (now.isBefore(startsAt)) {
			return EventStatus.UPCOMING;
		}
		if (now.isBefore(endsAt)) {
			return EventStatus.OPEN;
		}
		return EventStatus.CLOSED;
	}

	private String normalizeTitle(String title) {
		String trimmed = title == null ? "" : title.replaceAll("\\s+", " ").trim();
		if (trimmed.isBlank()) {
			throw new BadRequestException("Event title is required.");
		}
		return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
	}

	public record EventStatusRefreshResult(int checkedEvents, int changedEvents) {
	}

	public record DueNotificationQueueResult(
			int activeEvents,
			int reminderEvents,
			int openEvents,
			int notificationsCreated
	) {
	}
}
