package com.swimpulse.notice;

import java.time.Instant;
import java.util.List;

public record NoticeExtractionResult(
		String title,
		Instant registrationStartsAt,
		Instant registrationEndsAt,
		double confidence,
		String reason,
		String sourceUrl,
		List<NoticeRegistrationPeriod> registrationPeriods
) {
	public NoticeExtractionResult {
		registrationPeriods = registrationPeriods == null ? List.of() : List.copyOf(registrationPeriods);
	}

	public NoticeExtractionResult(
			String title,
			Instant registrationStartsAt,
			Instant registrationEndsAt,
			double confidence,
			String reason,
			String sourceUrl
	) {
		this(title, registrationStartsAt, registrationEndsAt, confidence, reason, sourceUrl, List.of());
	}

	public boolean hasPeriod() {
		return registrationStartsAt != null && registrationEndsAt != null;
	}
}
