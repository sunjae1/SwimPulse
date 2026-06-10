package com.swimpulse.event;

import java.time.Instant;

public record EventResponse(
		Long id,
		Long noticeRegistrationPeriodId,
		Long poolId,
		String poolName,
		String title,
		Instant registrationStartsAt,
		Instant registrationEndsAt,
		EventStatus status,
		boolean reminderQueued,
		boolean startQueued
) {
	public static EventResponse from(RegistrationEvent event) {
		return new EventResponse(
				event.getId(),
				event.getNoticeRegistrationPeriod() == null ? null : event.getNoticeRegistrationPeriod().getId(),
				event.getPool().getId(),
				event.getPool().getName(),
				event.getTitle(),
				event.getRegistrationStartsAt(),
				event.getRegistrationEndsAt(),
				event.getStatus(),
				event.isReminderQueued(),
				event.isStartQueued()
		);
	}
}
