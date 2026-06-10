package com.swimpulse.notice;

public record NoticePeriodMigrationStatus(
		long totalNotices,
		long migratedNotices,
		long pendingNotices,
		long failedNotices,
		long activePeriods,
		long inactivePeriods,
		long linkedEvents
) {
}
