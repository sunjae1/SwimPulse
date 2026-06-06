package com.swimpulse.subscription;

import com.swimpulse.common.NotFoundException;
import com.swimpulse.common.BadRequestException;
import com.swimpulse.event.EventStatus;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.event.RegistrationEventRepository;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {
	private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

	private final SubscriptionRepository subscriptionRepository;
	private final AppUserRepository userRepository;
	private final PoolRepository poolRepository;
	private final RegistrationEventRepository eventRepository;

	public SubscriptionService(
			SubscriptionRepository subscriptionRepository,
			AppUserRepository userRepository,
			PoolRepository poolRepository,
			RegistrationEventRepository eventRepository
	) {
		this.subscriptionRepository = subscriptionRepository;
		this.userRepository = userRepository;
		this.poolRepository = poolRepository;
		this.eventRepository = eventRepository;
	}

	@Transactional(readOnly = true)
	public List<SubscriptionResponse> findByUser(Long userId) {
		ensureUserExists(userId);
		return subscriptionRepository.findByUser_IdAndEventIsNotNullOrderByCreatedAtDesc(userId)
				.stream()
				.map(SubscriptionResponse::from)
				.toList();
	}

	@Transactional
	public SubscriptionResponse subscribe(Long userId, CreateSubscriptionRequest request) {
		if (!request.registrationStartsAt().isBefore(request.registrationEndsAt())) {
			throw new BadRequestException("registrationStartsAt must be before registrationEndsAt");
		}
		if (!request.registrationEndsAt().isAfter(Instant.now())) {
			throw new BadRequestException("Cannot subscribe to an already closed registration period.");
		}
		AppUser user = userRepository.findById(userId)
				.orElseThrow(() -> new NotFoundException("User not found: " + userId));
		Pool pool = getPoolForEventCreation(request.poolId());
		String title = normalizeTitle(request.title());
		RegistrationEvent event = eventRepository.findByPool_IdAndTitleAndRegistrationStartsAtAndRegistrationEndsAt(
						pool.getId(),
						title,
						request.registrationStartsAt(),
						request.registrationEndsAt()
				)
				.orElseGet(() -> {
					RegistrationEvent saved = eventRepository.save(new RegistrationEvent(
							pool,
							title,
							request.registrationStartsAt(),
							request.registrationEndsAt(),
							calculateStatus(request.registrationStartsAt(), request.registrationEndsAt(), Instant.now())
					));
					log.info("Registration event created from subscription. eventId={} poolId={} status={} startsAt={} endsAt={}",
							saved.getId(), pool.getId(), saved.getStatus(), saved.getRegistrationStartsAt(), saved.getRegistrationEndsAt());
					return saved;
				});
		return subscriptionRepository.findByUser_IdAndEvent_Id(userId, event.getId())
				.map(subscription -> {
					log.info("Subscription already exists. userId={} eventId={} poolId={}", userId, event.getId(), pool.getId());
					return SubscriptionResponse.from(subscription);
				})
				.orElseGet(() -> {
					Subscription saved = subscriptionRepository.save(new Subscription(user, event));
					log.info("Subscription created. userId={} poolId={} eventId={} subscriptionId={}",
							userId, pool.getId(), event.getId(), saved.getId());
					return SubscriptionResponse.from(saved);
				});
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
		Pool pool = getPoolForEventCreation(subscription.getPool().getId());
		RegistrationEvent event = eventRepository.findByPool_IdAndTitleAndRegistrationStartsAtAndRegistrationEndsAt(
						pool.getId(),
						title,
						request.registrationStartsAt(),
						request.registrationEndsAt()
				)
				.orElseGet(() -> {
					RegistrationEvent saved = eventRepository.save(new RegistrationEvent(
							pool,
							title,
							request.registrationStartsAt(),
							request.registrationEndsAt(),
							calculateStatus(request.registrationStartsAt(), request.registrationEndsAt(), Instant.now())
					));
					log.info("Registration event created from subscription update. eventId={} poolId={} startsAt={} endsAt={}",
							saved.getId(), pool.getId(), saved.getRegistrationStartsAt(), saved.getRegistrationEndsAt());
					return saved;
				});

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
		Subscription subscription = subscriptionRepository.findByUser_IdAndEvent_Id(userId, eventId)
				.orElseThrow(() -> new NotFoundException("Subscription not found."));
		subscriptionRepository.delete(subscription);
		log.info("Subscription deleted. userId={} eventId={} subscriptionId={}", userId, eventId, subscription.getId());
	}

	private void ensureUserExists(Long userId) {
		if (!userRepository.existsById(userId)) {
			throw new NotFoundException("User not found: " + userId);
		}
	}

	private Pool getPoolForEventCreation(Long poolId) {
		return poolRepository.findByIdForUpdate(poolId)
				.orElseThrow(() -> new NotFoundException("Pool not found: " + poolId));
	}

	private String normalizeTitle(String title) {
		String trimmed = title == null ? "" : title.replaceAll("\\s+", " ").trim();
		if (trimmed.isBlank()) {
			throw new BadRequestException("Subscription title is required.");
		}
		return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
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
}
