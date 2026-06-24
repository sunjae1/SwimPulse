package com.swimpulse.admin;

import java.time.Instant;
import java.util.List;
import com.swimpulse.notification.NotificationResponse;
import com.swimpulse.pool.PoolAddRequestResponse;

public record AdminDashboardResponse(
		Instant generatedAt,
		AdminOverview overview,
		AdminNotificationDashboard notifications,
		AdminNoticeDashboard notices,
		AdminWorkerDashboard workers,
		AdminNotificationDeliveryStats deliveryStats,
		List<AdminPoolRankingResponse> topSubscribedPools,
		List<AdminDistrictRankingResponse> topSubscribedDistricts,
		List<PoolAddRequestResponse> pendingPoolAddRequests,
		List<PoolAddRequestResponse> poolAddRequests,
		List<NotificationResponse> failedNotifications,
		List<AdminActionLogResponse> recentActionLogs
) {
	public record AdminOverview(
			long users,
			long pools,
			long subscriptions,
			long events,
			long activeDevices
	) {
	}

	public record AdminNotificationDashboard(
			long queueLength,
			long total,
			long staleSending,
			List<AdminMetricCount> byStatus
	) {
	}

	public record AdminNotificationDeliveryStats(
			long queued,
			long sending,
			long sent,
			long failed,
			double successRate,
			double failureRate
	) {
	}

	public record AdminNoticeDashboard(
			long totalNotices,
			long pendingPeriodMigration,
			long failedPeriodMigration,
			List<AdminMetricCount> sourcesByStatus,
			List<AdminMetricCount> noticesByExtractionStatus,
			List<AdminMetricCount> noticesByOcrStatus
	) {
	}

	public record AdminWorkerDashboard(
			int notificationBatchSize,
			long notificationDelayMs,
			long notificationStaleSendingTimeoutMs,
			int eventSchedulerPoolSize,
			long eventSchedulerDelayMs
	) {
	}
}
