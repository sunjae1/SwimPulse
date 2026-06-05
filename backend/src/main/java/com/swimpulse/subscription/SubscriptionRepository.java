package com.swimpulse.subscription;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
	List<Subscription> findByUser_IdOrderByCreatedAtDesc(Long userId);

	List<Subscription> findByUser_IdAndEventIsNotNullOrderByCreatedAtDesc(Long userId);

	List<Subscription> findByEvent_Id(Long eventId);

	Optional<Subscription> findByUser_IdAndEvent_Id(Long userId, Long eventId);
}
