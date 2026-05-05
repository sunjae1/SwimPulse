package com.swimpulse.notification;

import java.util.Map;

public record FcmMessage(
		String token,
		String title,
		String body,
		Map<String, String> data
) {
}
