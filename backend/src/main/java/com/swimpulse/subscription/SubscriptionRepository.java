package com.swimpulse.subscription;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
	List<Subscription> findByUser_IdOrderByCreatedAtDesc(Long userId);

	List<Subscription> findByPool_Id(Long poolId);

	Optional<Subscription> findByUser_IdAndPool_Id(Long userId, Long poolId);

	boolean existsByUser_IdAndPool_Id(Long userId, Long poolId);
}
