package com.swimpulse.subscription;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
	List<Subscription> findByUser_IdOrderByCreatedAtDesc(Long userId);

	List<Subscription> findByUser_IdAndEventIsNotNullOrderByCreatedAtDesc(Long userId);

	List<Subscription> findByEvent_Id(Long eventId);

	Optional<Subscription> findByUser_IdAndEvent_Id(Long userId, Long eventId);

	Optional<Subscription> findByIdAndUser_Id(Long id, Long userId);

	@Modifying
	@Query("delete from Subscription subscription where subscription.user.id = :userId and subscription.event.id = :eventId")
	int deleteByUserIdAndEventId(@Param("userId") Long userId, @Param("eventId") Long eventId);
}
