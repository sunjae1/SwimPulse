package com.swimpulse.notice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TesseractNoticeImageOcrServiceTests {

	@Test
	void extractTextProcessesOnlyConfiguredMaxImages() {
		RecordingTesseractNoticeImageOcrService service = new RecordingTesseractNoticeImageOcrService(true, 2);

		NoticeImageOcrService.NoticeImageOcrResult result = service.extractText(List.of(
				"https://example.com/a.png",
				"https://example.com/b.png",
				"https://example.com/c.png"
		));

		assertEquals(List.of(
				"https://example.com/a.png",
				"https://example.com/b.png"
		), service.processedUrls());
		assertEquals(2, result.attemptedImages());
		assertEquals(2, result.extractedImages());
	}

	@Test
	void extractTextReturnsEmptyWhenDisabled() {
		RecordingTesseractNoticeImageOcrService service = new RecordingTesseractNoticeImageOcrService(false, 3);

		NoticeImageOcrService.NoticeImageOcrResult result = service.extractText(List.of("https://example.com/a.png"));

		assertFalse(result.hasText());
		assertEquals(0, result.attemptedImages());
		assertEquals(List.of(), service.processedUrls());
	}

	@Test
	void deleteRecursivelyRemovesTempFilesAndReportsSuccess() throws IOException {
		RecordingTesseractNoticeImageOcrService service = new RecordingTesseractNoticeImageOcrService(true, 3);
		Path tempDirectory = Files.createTempDirectory("notice-ocr-test-");
		Files.writeString(tempDirectory.resolve("input.png"), "fake-image", StandardCharsets.UTF_8);
		Files.writeString(tempDirectory.resolve("output.txt"), "fake-text", StandardCharsets.UTF_8);

		TesseractNoticeImageOcrService.TempCleanupResult cleanupResult = service.deleteRecursively(tempDirectory);

		assertEquals(3, cleanupResult.deletedEntries());
		assertTrue(cleanupResult.failures().isEmpty());
		assertFalse(Files.exists(tempDirectory));
	}

	private static final class RecordingTesseractNoticeImageOcrService extends TesseractNoticeImageOcrService {
		private final List<String> processedUrls = new ArrayList<>();

		private RecordingTesseractNoticeImageOcrService(boolean enabled, int maxImages) {
			super(enabled, "tesseract", "kor+eng", maxImages, Duration.ofSeconds(15), false);
		}

		@Override
		byte[] downloadImage(String imageUrl) {
			processedUrls.add(imageUrl);
			return "fake-image".getBytes(StandardCharsets.UTF_8);
		}

		@Override
		String runTesseract(byte[] imageBytes, String imageUrl) {
			return "접수기간 : 매월 17일 ~ 22일";
		}

		private List<String> processedUrls() {
			return processedUrls;
		}
	}
}
