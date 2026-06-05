package com.swimpulse.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swimpulse.notice.NoticeRegistrationPeriod;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HttpClientConfigTests {
	@Test
	void objectMapperSerializesJavaTimeInstants() throws Exception {
		ObjectMapper objectMapper = new HttpClientConfig().objectMapper();
		String json = objectMapper.writeValueAsString(new NoticeRegistrationPeriod(
				"신규 회원",
				Instant.parse("2026-04-27T00:00:00Z"),
				Instant.parse("2026-04-30T14:59:59Z"),
				"4. 27.(월) ~ 4. 30.(목)",
				"table row"
		));

		assertTrue(json.contains("\"startsAt\":\"2026-04-27T00:00:00Z\""));
		assertTrue(json.contains("\"endsAt\":\"2026-04-30T14:59:59Z\""));
	}
}
