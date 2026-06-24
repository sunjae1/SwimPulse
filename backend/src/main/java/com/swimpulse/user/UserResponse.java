package com.swimpulse.user;

import java.time.Instant;

public record UserResponse(
		Long id,
		String email,
		String displayName,
		String profileImageUrl,
		boolean notificationEnabled,
		AppUserRole role,
		boolean fcmTokenRegistered,
		Instant createdAt,
		Instant lastLoginAt
) {
	public static UserResponse from(AppUser user) {
		return from(user, false);
	}

	public static UserResponse from(AppUser user, boolean fcmTokenRegistered) {
		return new UserResponse(
				user.getId(),
				user.getEmail(),
				user.getDisplayName(),
				user.getProfileImageUrl(),
				user.isNotificationEnabled(),
				user.getRole(),
				fcmTokenRegistered,
				user.getCreatedAt(),
				user.getLastLoginAt()
		);
	}
}
