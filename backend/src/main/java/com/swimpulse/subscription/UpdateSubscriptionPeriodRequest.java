package com.swimpulse.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record UpdateSubscriptionPeriodRequest(
		@NotBlank String title,
		@NotNull Instant registrationStartsAt,
		@NotNull Instant registrationEndsAt
) {
}
