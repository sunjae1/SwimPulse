package com.swimpulse.notice;

import java.time.Instant;

public record NoticeExtractionResult(
		String title,
		Instant registrationStartsAt,
		Instant registrationEndsAt,
		double confidence,
		String reason,
		String sourceUrl
) {
	public boolean hasPeriod() {
		return registrationStartsAt != null && registrationEndsAt != null;
	}
}
