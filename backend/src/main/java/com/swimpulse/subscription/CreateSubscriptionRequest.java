package com.swimpulse.subscription;

import jakarta.validation.constraints.NotNull;

public record CreateSubscriptionRequest(
		@NotNull Long poolId
) {
}
