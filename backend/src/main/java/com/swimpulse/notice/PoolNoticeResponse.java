package com.swimpulse.notice;

import java.time.Instant;

public record PoolNoticeResponse(
		Long id,
		Long poolId,
		String poolName,
		String title,
		String url,
		Instant publishedAt,
		NoticeExtractionStatus extractionStatus,
		Double confidence,
		Instant registrationStartsAt,
		Instant registrationEndsAt,
		String reason
) {
	public static PoolNoticeResponse from(PoolNotice notice) {
		return new PoolNoticeResponse(
				notice.getId(),
				notice.getPool().getId(),
				notice.getPool().getName(),
				notice.getTitle(),
				notice.getUrl(),
				notice.getPublishedAt(),
				notice.getExtractionStatus(),
				notice.getConfidence(),
				notice.getRegistrationStartsAt(),
				notice.getRegistrationEndsAt(),
				notice.getReason()
		);
	}
}
