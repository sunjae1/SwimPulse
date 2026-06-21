package com.swimpulse.subscription;

import com.swimpulse.common.NotFoundException;
import com.swimpulse.common.BadRequestException;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.event.RegistrationEventResolver;
import com.swimpulse.notice.NoticeRegistrationPeriodEntity;
import com.swimpulse.notice.NoticeRegistrationPeriodRepository;
import com.swimpulse.notice.NoticeRegistrationPeriodStatus;
import com.swimpulse.user.AppUserRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {
	private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

	private final SubscriptionRepository subscriptionRepository;
	private final AppUserRepository userRepository;
	private final RegistrationEventResolver eventResolver;
	private final NoticeRegistrationPeriodRepository periodRepository;
	private final SubscriptionInsertService insertService;

	public SubscriptionService(
			SubscriptionRepository subscriptionRepository,
			AppUserRepository userRepository,
			RegistrationEventResolver eventResolver,
			NoticeRegistrationPeriodRepository periodRepository,
			SubscriptionInsertService insertService
	) {
		this.subscriptionRepository = subscriptionRepository;
		this.userRepository = userRepository;
		this.eventResolver = eventResolver;
		this.periodRepository = periodRepository;
		this.insertService = insertService;
	}

	@Transactional(readOnly = true)
	public List<SubscriptionResponse> findByUser(Long userId) {
		ensureUserExists(userId);
		return subscriptionRepository.findByUser_IdAndEventIsNotNullOrderByCreatedAtDesc(userId)
				.stream()
				.map(SubscriptionResponse::from)
				.toList();
	}

	public SubscriptionResponse subscribe(Long userId, CreateSubscriptionRequest request) {
		if (!userRepository.existsById(userId)) {
			throw new NotFoundException("User not found: " + userId);
		}
		String title = normalizeTitle(request.title());
		RegistrationEvent event = resolveEvent(request, title);
		Long eventId = event.getId();
		return insertService.findExistingResponse(userId, eventId)
				.map(subscription -> {
					log.info("Subscription already exists. userId={} eventId={} poolId={}", userId, eventId, request.poolId());
					return subscription;
				})
				.orElseGet(() -> {
					try {
						Subscription saved = insertService.insert(userId, eventId, request.poolId());
						log.info("Subscription created. userId={} poolId={} eventId={} subscriptionId={}",
								userId, request.poolId(), eventId, saved.getId());
						return insertService.findExistingResponse(userId, eventId)
								.orElseThrow(() -> new NotFoundException("Subscription not found after creation."));
					} catch (DataIntegrityViolationException exception) {
						log.info("Concurrent subscription insert detected. Reusing existing row. userId={} eventId={}",
								userId, eventId);
						return insertService.findExistingResponse(userId, eventId)
								.orElseThrow(() -> exception);
					}
				});
	}

	private RegistrationEvent resolveEvent(CreateSubscriptionRequest request, String title) {
		if (request.noticeRegistrationPeriodId() != null) {
			NoticeRegistrationPeriodEntity period = periodRepository.findByIdAndStatusWithNoticeAndPool(
							request.noticeRegistrationPeriodId(),
							NoticeRegistrationPeriodStatus.ACTIVE
					)
					.orElseThrow(() -> new NotFoundException(
							"Active notice registration period not found: " + request.noticeRegistrationPeriodId()
					));
			if (!period.getNotice().getPool().getId().equals(request.poolId())) {
				throw new BadRequestException("Notice registration period does not belong to the requested pool.");
			}
			if (!period.getStartsAt().equals(request.registrationStartsAt())
					|| !period.getEndsAt().equals(request.registrationEndsAt())) {
				throw new BadRequestException("Registration period changed. Refresh the notice result and try again.");
			}
			validateOpenPeriod(period.getStartsAt(), period.getEndsAt());
			return eventResolver.getOrCreateForNoticePeriod(period, title);
		}

		validateOpenPeriod(request.registrationStartsAt(), request.registrationEndsAt());
		return eventResolver.getOrCreate(
				request.poolId(),
				title,
				request.registrationStartsAt(),
				request.registrationEndsAt()
		);
	}

	private void validateOpenPeriod(Instant startsAt, Instant endsAt) {
		if (!startsAt.isBefore(endsAt)) {
			throw new BadRequestException("registrationStartsAt must be before registrationEndsAt");
		}
		if (!endsAt.isAfter(Instant.now())) {
			throw new BadRequestException("Cannot subscribe to an already closed registration period.");
		}
	}

	@Transactional
	public SubscriptionResponse updatePeriod(Long userId, Long subscriptionId, UpdateSubscriptionPeriodRequest request) {
		if (!request.registrationStartsAt().isBefore(request.registrationEndsAt())) {
			throw new BadRequestException("registrationStartsAt must be before registrationEndsAt");
		}
		if (!request.registrationEndsAt().isAfter(Instant.now())) {
			throw new BadRequestException("Cannot subscribe to an already closed registration period.");
		}

		Subscription subscription = subscriptionRepository.findByIdAndUser_Id(subscriptionId, userId)
				.orElseThrow(() -> new NotFoundException("Subscription not found."));
		String title = normalizeTitle(request.title());
		RegistrationEvent event = eventResolver.getOrCreate(
				subscription.getPool().getId(),
				title,
				request.registrationStartsAt(),
				request.registrationEndsAt()
		);

		subscriptionRepository.findByUser_IdAndEvent_Id(userId, event.getId())
				.filter(existing -> !existing.getId().equals(subscription.getId()))
				.ifPresent(existing -> {
					throw new BadRequestException("Already subscribed to the same registration period.");
				});

		subscription.reassignEvent(event);
		log.info("Subscription period updated. userId={} subscriptionId={} eventId={} startsAt={} endsAt={}",
				userId, subscriptionId, event.getId(), event.getRegistrationStartsAt(), event.getRegistrationEndsAt());
		return SubscriptionResponse.from(subscription);
	}

	@Transactional
	public void unsubscribe(Long userId, Long eventId) {
		int deleted = subscriptionRepository.deleteByUserIdAndEventId(userId, eventId);
		if (deleted == 0) {
			log.info("Unsubscription ignored because subscription is already absent. userId={} eventId={}", userId, eventId);
			return;
		}
		log.info("Subscription deleted. userId={} eventId={} deletedRows={}", userId, eventId, deleted);
	}

	private void ensureUserExists(Long userId) {
		if (!userRepository.existsById(userId)) {
			throw new NotFoundException("User not found: " + userId);
		}
	}

	private String normalizeTitle(String title) {
		String trimmed = title == null ? "" : title.replaceAll("\\s+", " ").trim();
		if (trimmed.isBlank()) {
			throw new BadRequestException("Subscription title is required.");
		}
		return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
	}
}
