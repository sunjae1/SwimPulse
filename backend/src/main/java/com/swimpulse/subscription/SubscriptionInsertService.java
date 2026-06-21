package com.swimpulse.subscription;

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
}
