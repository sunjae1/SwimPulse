package com.swimpulse.auth;

import com.swimpulse.user.UserResponse;

public record MobileLoginResponse(
		String accessToken,
		UserResponse user
) {
}
