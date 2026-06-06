package com.swimpulse.mypage;

public record MyPageMetrics(
		int subscriptionCount,
		long upcomingSubscriptionCount,
		long openSubscriptionCount,
		int notificationCount,
		long unreadNotificationCount,
		long activeDeviceCount
) {
}
