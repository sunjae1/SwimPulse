package com.swimpulse.pool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePoolFromLocationCandidateRequest(
		@NotBlank @Size(max = 100) String title,
		@Size(max = 255) String address,
		@Size(max = 255) String roadAddress,
		@Size(max = 500) String link,
		Double latitude,
		Double longitude
) {
}
