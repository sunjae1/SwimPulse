package com.swimpulse.notice;

public record NoticePeriodMigrationResult(
		Long noticeId,
		Long poolId,
		String title,
		int activePeriods,
		int linkedEvents,
		boolean legacyFallbackUsed,
		boolean success,
		String message
) {
}
