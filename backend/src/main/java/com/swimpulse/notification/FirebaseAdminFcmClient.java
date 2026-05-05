package com.swimpulse.notification;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import java.util.HashMap;
import java.util.Map;

public class FirebaseAdminFcmClient implements FcmClient {
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
			return firebaseMessaging.send(firebaseMessage);
		} catch (Exception exception) {
			throw new FcmSendException(exception.getMessage(), exception);
		}
	}
}
