package com.swimpulse.notification;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceTokenRequest(
		@NotBlank String deviceId,
		@NotBlank String fcmToken,
		DevicePlatform platform
) {
	public DevicePlatform resolvedPlatform() {
		return platform == null ? DevicePlatform.WEB : platform;
	}
}
