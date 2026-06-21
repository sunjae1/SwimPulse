package com.swimpulse.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.swimpulse.notification.FcmClient;
import com.swimpulse.notification.FirebaseAdminFcmClient;
import com.swimpulse.notification.MockFcmClient;
import java.io.FileInputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class FcmConfig {
	private static final Logger log = LoggerFactory.getLogger(FcmConfig.class);

	@Bean
	FcmClient fcmClient(
			@Value("${swimpulse.firebase.mock:false}") boolean mockEnabled,
			@Value("${swimpulse.firebase.service-account-path:}") String serviceAccountPath
	) throws IOException {
		if (mockEnabled) {
			log.info("Firebase mock mode is enabled. Using MockFcmClient.");
			return new MockFcmClient();
		}
		if (!StringUtils.hasText(serviceAccountPath)) {
			log.info("Firebase service account is not configured. Using MockFcmClient.");
			return new MockFcmClient();
		}

		try (FileInputStream serviceAccount = new FileInputStream(serviceAccountPath)) {
			FirebaseOptions options = FirebaseOptions.builder()
					.setCredentials(GoogleCredentials.fromStream(serviceAccount))
					.build();
			FirebaseApp app = FirebaseApp.getApps().isEmpty()
					? FirebaseApp.initializeApp(options)
					: FirebaseApp.getInstance();
			log.info("Firebase Admin SDK initialized.");
			return new FirebaseAdminFcmClient(FirebaseMessaging.getInstance(app));
		}
	}
}
