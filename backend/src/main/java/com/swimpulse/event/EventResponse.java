package com.swimpulse.event;

import java.time.Instant;

public record EventResponse(
		Long id,
		Long noticeRegistrationPeriodId,
		String noticeUrl,
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
				noticeUrl(event),
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

	private static String noticeUrl(RegistrationEvent event) {
		if (event.getNoticeRegistrationPeriod() == null || event.getNoticeRegistrationPeriod().getNotice() == null) {
			return event.getNoticeUrl();
		}
		return event.getNoticeRegistrationPeriod().getNotice().getUrl();
	}
}
