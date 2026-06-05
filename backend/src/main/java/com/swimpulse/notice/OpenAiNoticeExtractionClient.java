package com.swimpulse.notice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swimpulse.common.BadRequestException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAiNoticeExtractionClient {
	private static final Logger log = LoggerFactory.getLogger(OpenAiNoticeExtractionClient.class);
	private static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final String apiKey;
	private final String model;

	public OpenAiNoticeExtractionClient(
			RestClient.Builder restClientBuilder,
			ObjectMapper objectMapper,
			@Value("${swimpulse.openai.api-key:}") String apiKey,
			@Value("${swimpulse.openai.model:gpt-5.4-mini}") String model
	) {
		this.restClient = restClientBuilder.build();
		this.objectMapper = objectMapper;
		this.apiKey = apiKey;
		this.model = model;
	}

	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank();
	}

	public NoticeExtractionResult extract(String title, String url, String text, List<String> imageUrls) {
		if (!isConfigured()) {
			throw new BadRequestException("OpenAI API key is not configured.");
		}
		log.info("OpenAI notice extraction requested. model={} title={} url={} textLength={} imageCount={}",
				model, title, url, text == null ? 0 : text.length(), imageUrls == null ? 0 : imageUrls.size());

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("input", List.of(
				Map.of(
						"role", "system",
						"content", "You extract Korean public sports center swimming registration notice periods. Return null dates when uncertain."
				),
				Map.of(
						"role", "user",
						"content", buildPrompt(title, url, text, imageUrls)
				)
		));
		body.put("text", Map.of("format", responseSchema()));

		try {
			JsonNode root = restClient.post()
					.uri(OPENAI_RESPONSES_URL)
					.header("Authorization", "Bearer " + apiKey)
					.body(body)
					.retrieve()
					.body(JsonNode.class);
			String outputText = findOutputText(root);
			if (outputText == null || outputText.isBlank()) {
				log.warn("OpenAI notice extraction returned no output text. title={} url={}", title, url);
				return new NoticeExtractionResult(title, null, null, 0.0, "OpenAI response did not contain output text.", url);
			}
			JsonNode parsed = objectMapper.readTree(outputText);
			NoticeExtractionResult result = new NoticeExtractionResult(
					nullableText(parsed, "title", title),
					nullableInstant(parsed, "registrationStartsAt"),
					nullableInstant(parsed, "registrationEndsAt"),
					parsed.path("confidence").asDouble(0.0),
					nullableText(parsed, "reason", "AI extraction completed."),
					nullableText(parsed, "sourceUrl", url)
			);
			log.info("OpenAI notice extraction completed. title={} url={} hasPeriod={} confidence={}",
					title, url, result.hasPeriod(), result.confidence());
			return result;
		} catch (RestClientResponseException exception) {
			log.warn("OpenAI notice extraction request failed. title={} url={} status={} {}",
					title, url, exception.getStatusCode().value(), exception.getStatusText());
			return new NoticeExtractionResult(title, null, null, 0.0, "OpenAI request failed: "
					+ exception.getStatusCode().value() + " " + exception.getStatusText(), url);
		} catch (Exception exception) {
			log.warn("OpenAI notice extraction processing failed. title={} url={} message={}",
					title, url, exception.getMessage());
			return new NoticeExtractionResult(title, null, null, 0.0, exception.getMessage(), url);
		}
	}

	private String buildPrompt(String title, String url, String text, List<String> imageUrls) {
		return """
				공지 제목: %s
				공지 URL: %s
				이미지 URL 목록: %s

				본문:
				%s

				이번 달 또는 다음 달 수영/체육센터 회원 모집, 접수, 등록 기간을 찾아라.
				기간이 명확하지 않으면 registrationStartsAt/registrationEndsAt은 null로 둔다.
				날짜는 가능하면 Asia/Seoul 기준 ISO-8601 instant로 반환한다.
				""".formatted(title, url, imageUrls, truncate(text, 12_000));
	}

	private Map<String, Object> responseSchema() {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("additionalProperties", false);
		schema.put("required", List.of("title", "registrationStartsAt", "registrationEndsAt", "confidence", "reason", "sourceUrl"));
		schema.put("properties", Map.of(
				"title", Map.of("type", List.of("string", "null")),
				"registrationStartsAt", Map.of("type", List.of("string", "null")),
				"registrationEndsAt", Map.of("type", List.of("string", "null")),
				"confidence", Map.of("type", "number"),
				"reason", Map.of("type", List.of("string", "null")),
				"sourceUrl", Map.of("type", List.of("string", "null"))
		));
		return Map.of(
				"type", "json_schema",
				"name", "notice_extraction",
				"strict", true,
				"schema", schema
		);
	}

	private String findOutputText(JsonNode root) {
		if (root == null || !root.has("output")) {
			return null;
		}
		for (JsonNode output : root.path("output")) {
			for (JsonNode content : output.path("content")) {
				String text = content.path("text").asText(null);
				if (text != null) {
					return text;
				}
			}
		}
		return null;
	}

	private Instant nullableInstant(JsonNode node, String field) {
		String value = node.path(field).asText(null);
		if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
			return null;
		}
		return Instant.parse(value);
	}

	private String nullableText(JsonNode node, String field, String fallback) {
		String value = node.path(field).asText(null);
		if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
			return fallback;
		}
		return value;
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}
}
