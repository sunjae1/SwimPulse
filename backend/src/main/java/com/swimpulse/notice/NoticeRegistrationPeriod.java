package com.swimpulse.notice;

import java.time.Instant;

public record NoticeRegistrationPeriod(
		Long id,
		String label,
		Instant startsAt,
		Instant endsAt,
		String periodText,
		String source
) {
	public NoticeRegistrationPeriod(
			String label,
			Instant startsAt,
			Instant endsAt,
			String periodText,
			String source
	) {
		this(null, label, startsAt, endsAt, periodText, source);
	}
}
