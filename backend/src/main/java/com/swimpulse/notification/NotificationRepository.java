package com.swimpulse.notification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
	List<Notification> findByUser_IdOrderByCreatedAtDesc(Long userId);

	Page<Notification> findByUser_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);

	Optional<Notification> findByDedupeKey(String dedupeKey);

	List<Notification> findBySubscription_IdInAndStatus(Collection<Long> subscriptionIds, NotificationStatus status);

	long countByUser_Id(Long userId);

	long countByUser_IdAndReadAtIsNull(Long userId);

	long countByStatus(NotificationStatus status);

	long countByEvent_Id(Long eventId);

	long countByEvent_IdAndStatus(Long eventId, NotificationStatus status);

	List<Notification> findTop50ByStatusAndProcessingStartedAtBeforeOrderByProcessingStartedAtAsc(
			NotificationStatus status,
			java.time.Instant processingStartedAt
	);

	long countByStatusAndProcessingStartedAtBefore(NotificationStatus status, java.time.Instant processingStartedAt);

	@Query("""
			select count(notification)
			from Notification notification
			where notification.status = :status
			  and (notification.processingStartedAt is null or notification.processingStartedAt < :processingStartedAt)
			""")
	long countStaleByStatus(
			@Param("status") NotificationStatus status,
			@Param("processingStartedAt") java.time.Instant processingStartedAt
	);

	List<Notification> findByStatusOrderByCreatedAtDesc(NotificationStatus status, Pageable pageable);

	List<Notification> findByStatusAndProcessingStartedAtBeforeOrderByProcessingStartedAtAsc(
			NotificationStatus status,
			java.time.Instant processingStartedAt,
			Pageable pageable
	);

	@Query("""
			select notification
			from Notification notification
			where notification.status = :status
			  and (notification.processingStartedAt is null or notification.processingStartedAt < :processingStartedAt)
			order by case when notification.processingStartedAt is null then 0 else 1 end,
			         notification.processingStartedAt asc,
			         notification.id asc
			""")
	List<Notification> findStaleByStatus(
			@Param("status") NotificationStatus status,
			@Param("processingStartedAt") java.time.Instant processingStartedAt,
			Pageable pageable
	);
}
