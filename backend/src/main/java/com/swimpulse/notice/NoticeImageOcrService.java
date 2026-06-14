package com.swimpulse.notice;

import java.util.List;

public interface NoticeImageOcrService {
	NoticeImageOcrResult extractText(List<String> imageUrls);

	NoticeImageOcrService NO_OP = imageUrls -> NoticeImageOcrResult.empty("OCR service is disabled.");

	record NoticeImageOcrResult(
			String text,
			int attemptedImages,
			int extractedImages,
			String reason
	) {
		public static NoticeImageOcrResult empty(String reason) {
			return new NoticeImageOcrResult(null, 0, 0, reason);
		}

		public boolean hasText() {
			return text != null && !text.isBlank();
		}
	}
}
