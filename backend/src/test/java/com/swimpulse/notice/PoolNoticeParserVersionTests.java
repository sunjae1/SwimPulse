package com.swimpulse.notice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swimpulse.pool.Pool;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class PoolNoticeParserVersionTests {
	@Test
	void currentParserVersionReusesNoticeEvenWhenOnlyOnePeriodExists() throws Exception {
		PoolNotice notice = extractedNotice("[{\"label\":\"신규 회원\"}]");
		notice.markAnalyzed(NoticeCrawlerService.CURRENT_PARSER_VERSION);

		assertFalse(shouldRefresh(notice));
		assertNotNull(notice.getLastAnalyzedAt());
	}

	@Test
	void legacyParserVersionRequiresOneRefresh() throws Exception {
		PoolNotice notice = extractedNotice("[{\"label\":\"신규 회원\"}]");

		assertTrue(shouldRefresh(notice));
	}

	@Test
	void failedCurrentAnalysisRemainsRetryable() throws Exception {
		PoolNotice notice = new PoolNotice(
				new Pool("테스트수영장", "테스트구", "테스트"),
				"테스트 공지",
				"https://example.com/notice/1",
				null,
				NoticeExtractionStatus.FAILED,
				0.0,
				null,
				null,
				"timeout"
		);
		notice.markAnalyzed(NoticeCrawlerService.CURRENT_PARSER_VERSION);

		assertTrue(shouldRefresh(notice));
	}

	private PoolNotice extractedNotice(String registrationPeriodsJson) {
		return new PoolNotice(
				new Pool("테스트수영장", "테스트구", "테스트"),
				"테스트 공지",
				"https://example.com/notice/1",
				"신규 회원 접수기간 매월 20일 ~ 25일",
				NoticeExtractionStatus.EXTRACTED,
				0.9,
				null,
				null,
				"matched",
				registrationPeriodsJson
		);
	}

	private boolean shouldRefresh(PoolNotice notice) throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(
				null,
				null,
				null,
				null,
				new ObjectMapper(),
				false
		);
		Method method = NoticeCrawlerService.class.getDeclaredMethod(
				"shouldRefreshExistingNotice",
				PoolNotice.class
		);
		method.setAccessible(true);
		return (boolean) method.invoke(service, notice);
	}
}
