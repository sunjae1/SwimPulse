package com.swimpulse.auth;

import jakarta.validation.constraints.NotBlank;

public record MobileGoogleLoginRequest(
		@NotBlank String idToken
) {
}
