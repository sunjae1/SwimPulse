package com.swimpulse.mypage;

import com.swimpulse.event.EventStatus;
import com.swimpulse.notification.NotificationResponse;
import com.swimpulse.subscription.SubscriptionResponse;
import com.swimpulse.user.UserResponse;
import java.util.List;
import java.util.Objects;

public record MyPageResponse(
		UserResponse user,
		MyPageMetrics metrics,
		List<SubscriptionResponse> subscriptions,
		List<NotificationResponse> notifications
) {
	public static MyPageResponse from(
			UserResponse user,
			List<SubscriptionResponse> subscriptions,
			List<NotificationResponse> notifications,
			long notificationCount,
			long unreadNotificationCount,
			long activeDeviceCount
	) {
		long upcomingSubscriptionCount = subscriptions.stream()
				.map(SubscriptionResponse::event)
				.filter(Objects::nonNull)
				.filter(event -> event.status() == EventStatus.UPCOMING)
				.count();
		long openSubscriptionCount = subscriptions.stream()
				.map(SubscriptionResponse::event)
				.filter(Objects::nonNull)
				.filter(event -> event.status() == EventStatus.OPEN)
				.count();
		return new MyPageResponse(
				user,
				new MyPageMetrics(
						subscriptions.size(),
						upcomingSubscriptionCount,
						openSubscriptionCount,
						notificationCount,
						unreadNotificationCount,
						activeDeviceCount
				),
				subscriptions,
				notifications
		);
	}
}
