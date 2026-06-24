package com.swimpulse.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
	List<Notification> findByUser_IdOrderByCreatedAtDesc(Long userId);

	Page<Notification> findByUser_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);

	Optional<Notification> findByDedupeKey(String dedupeKey);

	long countByUser_Id(Long userId);

	long countByUser_IdAndReadAtIsNull(Long userId);

	long countByEvent_Id(Long eventId);

	long countByEvent_IdAndStatus(Long eventId, NotificationStatus status);

	List<Notification> findTop50ByStatusAndProcessingStartedAtBeforeOrderByProcessingStartedAtAsc(
			NotificationStatus status,
			java.time.Instant processingStartedAt
	);
}
