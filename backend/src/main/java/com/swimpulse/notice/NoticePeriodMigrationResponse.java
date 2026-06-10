package com.swimpulse.notice;

import java.util.List;

public record NoticePeriodMigrationResponse(
		int processedNotices,
		int migratedNotices,
		int activePeriods,
		int linkedEvents,
		int fallbackNotices,
		int failedNotices,
		List<NoticePeriodMigrationResult> results
) {
	public static NoticePeriodMigrationResponse from(List<NoticePeriodMigrationResult> results) {
		return new NoticePeriodMigrationResponse(
				results.size(),
				(int) results.stream().filter(NoticePeriodMigrationResult::success).count(),
				results.stream().mapToInt(NoticePeriodMigrationResult::activePeriods).sum(),
				results.stream().mapToInt(NoticePeriodMigrationResult::linkedEvents).sum(),
				(int) results.stream().filter(NoticePeriodMigrationResult::legacyFallbackUsed).count(),
				(int) results.stream().filter(result -> !result.success()).count(),
				results
		);
	}
}
