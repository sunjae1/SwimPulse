package com.swimpulse.subscription;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record CreateSubscriptionRequest(
		@NotNull Long poolId,
		@NotBlank String title,
		@NotNull Instant registrationStartsAt,
		@NotNull Instant registrationEndsAt,
		Long noticeRegistrationPeriodId
) {
}
