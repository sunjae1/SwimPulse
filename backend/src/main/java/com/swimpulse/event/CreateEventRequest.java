package com.swimpulse.event;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateEventRequest(
		@NotNull Long poolId,
		@NotBlank String title,
		@NotNull @Future Instant registrationStartsAt,
		@NotNull @Future Instant registrationEndsAt
) {
}
