package com.swimpulse.notice;

import java.time.Instant;

public record NoticeRegistrationPeriod(
		String label,
		Instant startsAt,
		Instant endsAt,
		String periodText,
		String source
) {
}
