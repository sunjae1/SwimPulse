package com.swimpulse.subscription;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.NotFoundException;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.pool.Pool;
import com.swimpulse.user.AppUser;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionInsertService {
	private final SubscriptionRepository subscriptionRepository;
	private final EntityManager entityManager;

	public SubscriptionInsertService(SubscriptionRepository subscriptionRepository, EntityManager entityManager) {
		this.subscriptionRepository = subscriptionRepository;
		this.entityManager = entityManager;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Subscription insert(AppUser user, RegistrationEvent event) {
		return subscriptionRepository.saveAndFlush(new Subscription(user, event));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Subscription insert(Long userId, Long eventId, Long poolId) {
		AppUser user = entityManager.getReference(AppUser.class, userId);
		RegistrationEvent event = entityManager.getReference(RegistrationEvent.class, eventId);
		Pool pool = entityManager.getReference(Pool.class, poolId);
		return subscriptionRepository.saveAndFlush(new Subscription(user, event, pool));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<Subscription> findExisting(Long userId, Long eventId) {
		return subscriptionRepository.findByUser_IdAndEvent_Id(userId, eventId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<SubscriptionResponse> findExistingResponse(Long userId, Long eventId) {
		return subscriptionRepository.findByUser_IdAndEvent_Id(userId, eventId)
				.map(SubscriptionResponse::from);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public SubscriptionUpdateSource findUpdateSource(Long userId, Long subscriptionId) {
		Subscription subscription = subscriptionRepository.findByIdAndUser_Id(subscriptionId, userId)
				.orElseThrow(() -> new NotFoundException("Subscription not found."));
		return new SubscriptionUpdateSource(
				subscription.getPool().getId(),
				noticeUrl(subscription.getEvent())
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SubscriptionResponse reassignEvent(Long userId, Long subscriptionId, Long eventId) {
		Subscription subscription = subscriptionRepository.findByIdAndUser_Id(subscriptionId, userId)
				.orElseThrow(() -> new NotFoundException("Subscription not found."));
		subscriptionRepository.findByUser_IdAndEvent_Id(userId, eventId)
				.filter(existing -> !existing.getId().equals(subscription.getId()))
				.ifPresent(existing -> {
					throw new BadRequestException("Already subscribed to the same registration period.");
				});
		RegistrationEvent event = entityManager.find(RegistrationEvent.class, eventId);
		if (event == null) {
			throw new NotFoundException("Registration event not found after resolve.");
		}
		subscription.reassignEvent(event);
		return SubscriptionResponse.from(subscription);
	}

	private String noticeUrl(RegistrationEvent event) {
		if (event.getNoticeUrl() != null && !event.getNoticeUrl().isBlank()) {
			return event.getNoticeUrl();
		}
		if (event.getNoticeRegistrationPeriod() == null || event.getNoticeRegistrationPeriod().getNotice() == null) {
			return null;
		}
		return event.getNoticeRegistrationPeriod().getNotice().getUrl();
	}

	public record SubscriptionUpdateSource(Long poolId, String noticeUrl) {
	}
}
