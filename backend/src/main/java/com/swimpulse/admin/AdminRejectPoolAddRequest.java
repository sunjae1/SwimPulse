package com.swimpulse.admin;

import jakarta.validation.constraints.Size;

public record AdminRejectPoolAddRequest(
		@Size(max = 1000) String reason
) {
}
