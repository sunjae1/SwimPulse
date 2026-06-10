package com.swimpulse.notice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swimpulse.pool.Pool;
import org.junit.jupiter.api.Test;

class NoticeSourceUrlNormalizerTests {
	@Test
	void removesSessionIdAndFragmentFromSourceUrl() {
		String normalized = NoticeSourceUrlNormalizer.normalize(
				"https://www.guriuc.or.kr:443/sports/main/main.do;jsessionid=ABC123?bbsId=NOTICE#none"
		);

		assertEquals(
				"https://www.guriuc.or.kr/sports/main/main.do?bbsId=NOTICE",
				normalized
		);
	}

	@Test
	void sourceBecomesFailedOnlyAfterConfiguredConsecutiveFailures() {
		PoolNoticeSource source = new PoolNoticeSource(
				new Pool("테스트수영장", "테스트구", "테스트"),
				"https://example.com/notice",
				NoticeSourceType.NOTICE_PAGE
		);

		source.markFailure("timeout", 3);
		source.markFailure("timeout", 3);

		assertEquals(NoticeSourceStatus.CANDIDATE, source.getStatus());
		assertEquals(2, source.getFailureCount());

		source.markFailure("timeout", 3);

		assertEquals(NoticeSourceStatus.FAILED, source.getStatus());
		assertEquals(3, source.getFailureCount());
	}

	@Test
	void successfulVerificationResetsFailureHistory() {
		PoolNoticeSource source = new PoolNoticeSource(
				new Pool("테스트수영장", "테스트구", "테스트"),
				"https://example.com/notice",
				NoticeSourceType.NOTICE_PAGE
		);
		source.markFailure("timeout", 3);

		source.markVerified();

		assertEquals(NoticeSourceStatus.VERIFIED, source.getStatus());
		assertEquals(0, source.getFailureCount());
		assertEquals(null, source.getLastError());
	}
}
