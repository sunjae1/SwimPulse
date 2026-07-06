package com.swimpulse.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RegisterDeviceTokenRequestTests {
	@Test
	void missingPlatformDefaultsToWebForExistingClients() {
		RegisterDeviceTokenRequest request = new RegisterDeviceTokenRequest("device", "token", null);

		assertEquals(DevicePlatform.WEB, request.resolvedPlatform());
	}

	@Test
	void androidPlatformIsAcceptedForMobileClients() {
		RegisterDeviceTokenRequest request = new RegisterDeviceTokenRequest("device", "token", DevicePlatform.ANDROID);

		assertEquals(DevicePlatform.ANDROID, request.resolvedPlatform());
	}
}
