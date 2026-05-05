package com.swimpulse.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
	List<Notification> findByUser_IdOrderByCreatedAtDesc(Long userId);
}
