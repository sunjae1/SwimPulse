package com.swimpulse.notification;

import java.time.Instant;

public record NotificationResponse(
		Long id,
		Long userId,
		Long poolId,
		String poolName,
		Long eventId,
		String eventTitle,
		NotificationType type,
		NotificationStatus status,
		String title,
		String message,
		String failureReason,
		int attempts,
		Instant createdAt,
		Instant sentAt,
		Instant readAt
) {
	public static NotificationResponse from(Notification notification) {
		return new NotificationResponse(
				notification.getId(),
				notification.getUser().getId(),
				notification.getPool().getId(),
				notification.getPool().getName(),
				notification.getEvent().getId(),
				notification.getEvent().getTitle(),
				notification.getType(),
				notification.getStatus(),
				notification.getTitle(),
				notification.getMessage(),
				notification.getFailureReason(),
				notification.getAttempts(),
				notification.getCreatedAt(),
				notification.getSentAt(),
				notification.getReadAt()
		);
	}
}
