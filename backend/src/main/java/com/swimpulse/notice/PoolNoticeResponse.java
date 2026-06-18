package com.swimpulse.notice;

import java.time.Instant;
import java.util.List;

public record PoolNoticeResponse(
		Long id,
		Long poolId,
		String poolName,
		String title,
		String url,
		Instant publishedAt,
		NoticeExtractionStatus extractionStatus,
		Double confidence,
		NoticeOcrStatus ocrStatus,
		Instant ocrRequestedAt,
		Instant ocrStartedAt,
		Instant ocrCompletedAt,
		Instant registrationStartsAt,
		Instant registrationEndsAt,
		List<NoticeRegistrationPeriod> registrationPeriods,
		String reason
) {
	public static PoolNoticeResponse from(PoolNotice notice) {
		return from(notice, List.of());
	}

	public static PoolNoticeResponse from(PoolNotice notice, List<NoticeRegistrationPeriod> registrationPeriods) {
		return new PoolNoticeResponse(
				notice.getId(),
				notice.getPool().getId(),
				notice.getPool().getName(),
				notice.getTitle(),
				notice.getUrl(),
				notice.getPublishedAt(),
				notice.getExtractionStatus(),
				notice.getConfidence(),
				notice.getOcrStatus(),
				notice.getOcrRequestedAt(),
				notice.getOcrStartedAt(),
				notice.getOcrCompletedAt(),
				notice.getRegistrationStartsAt(),
				notice.getRegistrationEndsAt(),
				registrationPeriods == null ? List.of() : registrationPeriods,
				notice.getReason()
		);
	}
}
