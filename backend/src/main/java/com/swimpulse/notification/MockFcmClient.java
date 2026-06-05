package com.swimpulse.notification;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockFcmClient implements FcmClient {
	private static final Logger log = LoggerFactory.getLogger(MockFcmClient.class);

	@Override
	public String send(FcmMessage message) {
		String messageId = "mock-" + UUID.randomUUID();
		log.info("Mock FCM sent. title={} messageId={}", message.title(), messageId);
		return messageId;
	}
}
