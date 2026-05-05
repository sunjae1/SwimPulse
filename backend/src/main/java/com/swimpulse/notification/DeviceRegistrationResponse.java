package com.swimpulse.notification;

import java.time.Instant;

public record DeviceRegistrationResponse(
		String deviceId,
		boolean registered,
		Instant lastSeenAt
) {
	public static DeviceRegistrationResponse unregistered(String deviceId) {
		return new DeviceRegistrationResponse(deviceId, false, null);
	}

	public static DeviceRegistrationResponse from(UserDevice device, String deviceId) {
		return new DeviceRegistrationResponse(deviceId, device.isEnabled(), device.getLastSeenAt());
	}
}
