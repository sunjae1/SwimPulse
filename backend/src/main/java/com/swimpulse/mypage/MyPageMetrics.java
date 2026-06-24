package com.swimpulse.mypage;

public record MyPageMetrics(
		int subscriptionCount,
		long upcomingSubscriptionCount,
		long openSubscriptionCount,
		long notificationCount,
		long unreadNotificationCount,
		long activeDeviceCount
) {
}
