package com.swimpulse.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminPoolHomepageCorrectionRequest(
		@NotBlank @Size(max = 255) String name,
		@NotBlank @Size(max = 255) String homepageUrl,
		@Size(max = 500) String reason
) {
}
