package com.swimpulse.notification;

import com.swimpulse.notice.NoticeRegistrationPeriodEntity;
import com.swimpulse.subscription.SubscriptionReviewStatus;
import java.time.Instant;

public record NotificationResponse(
		Long id,
		Long userId,
		Long poolId,
		String poolName,
		Long eventId,
		String eventTitle,
		Instant registrationStartsAt,
		Long subscriptionId,
		SubscriptionReviewStatus subscriptionReviewStatus,
		String noticeUrl,
		String currentHomepageUrl,
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
	public NotificationResponse(
			Long id,
			Long userId,
			Long poolId,
			String poolName,
			Long eventId,
			String eventTitle,
			String noticeUrl,
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
		this(id, userId, poolId, poolName, eventId, eventTitle, null, null, null, noticeUrl, null,
				type, status, title, message, failureReason, attempts, createdAt, sentAt, readAt);
	}

	public static NotificationResponse from(Notification notification) {
		return new NotificationResponse(
				notification.getId(),
				notification.getUser().getId(),
				notification.getPool().getId(),
				notification.getPool().getName(),
				notification.getEvent().getId(),
				notification.getEvent().getTitle(),
				notification.getEvent().getRegistrationStartsAt(),
				notification.getSubscription() == null ? null : notification.getSubscription().getId(),
				notification.getSubscription() == null ? null : notification.getSubscription().getReviewStatus(),
				noticeUrl(notification),
				notification.getPool().getHomepageUrl(),
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

	private static String noticeUrl(Notification notification) {
		NoticeRegistrationPeriodEntity period = notification.getEvent().getNoticeRegistrationPeriod();
		if (period == null || period.getNotice() == null) {
			return notification.getEvent().getNoticeUrl();
		}
		return period.getNotice().getUrl();
	}
}
