package com.swimpulse.notification;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceTokenRequest(
		@NotBlank String deviceId,
		@NotBlank String fcmToken
) {
}
