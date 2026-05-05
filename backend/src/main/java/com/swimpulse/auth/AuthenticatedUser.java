package com.swimpulse.auth;

public record AuthenticatedUser(
		Long id,
		String email,
		String displayName
) {
}
