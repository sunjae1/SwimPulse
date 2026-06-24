package com.swimpulse.admin;

import com.swimpulse.notification.NotificationResponse;
import java.time.Instant;
import java.util.List;

public record AdminOperationsDashboardResponse(
		Instant generatedAt,
		AdminDashboardResponse.AdminNotificationDashboard notifications,
		AdminDashboardResponse.AdminNotificationDeliveryStats deliveryStats,
		AdminDashboardResponse.AdminWorkerDashboard workers,
		List<NotificationResponse> failedNotifications,
		List<AdminActionLogResponse> recentActionLogs
) {
}
