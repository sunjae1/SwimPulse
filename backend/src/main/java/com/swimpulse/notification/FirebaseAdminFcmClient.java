package com.swimpulse.notification;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FirebaseAdminFcmClient implements FcmClient {
	private static final Logger log = LoggerFactory.getLogger(FirebaseAdminFcmClient.class);

	private final FirebaseMessaging firebaseMessaging;

	public FirebaseAdminFcmClient(FirebaseMessaging firebaseMessaging) {
		this.firebaseMessaging = firebaseMessaging;
	}

	@Override
	public String send(FcmMessage message) {
		try {
			Map<String, String> data = new HashMap<>(message.data());
			data.put("title", message.title());
			data.put("body", message.body());

			Message firebaseMessage = Message.builder()
					.setToken(message.token())
					.putAllData(data)
					.build();
			String messageId = firebaseMessaging.send(firebaseMessage);
			log.info("Firebase FCM sent. title={} messageId={}", message.title(), messageId);
			return messageId;
		} catch (Exception exception) {
			log.warn("Firebase FCM send failed. title={} message={}", message.title(), exception.getMessage());
			throw new FcmSendException(exception.getMessage(), exception);
		}
	}
}
