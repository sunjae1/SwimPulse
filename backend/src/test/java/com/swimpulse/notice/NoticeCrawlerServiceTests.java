package com.swimpulse.notice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class NoticeCrawlerServiceTests {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Test
	void extractsMultipleRegistrationPeriodsFromTableRows() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<table>
							<tbody>
								<tr>
									<td>회원 구분</td>
									<td>접수 기간</td>
									<td>접수 시간</td>
									<td>운영 시간</td>
								</tr>
								<tr>
									<td>기존 회원 및 추첨종목 모집기간</td>
									<td>
										<p>4. 20.(월) ~ 4. 24.(금)</p>
										<p>아쿠아 잔여 모집기간: 25.(토)~28.(화)</p>
									</td>
									<td rowspan="3">06시 ~ 21시</td>
									<td rowspan="3">06:00~22:00</td>
								</tr>
								<tr>
									<td>반 변경 및 추첨일</td>
									<td>수영/사물함 25.(토) / 아쿠아 29.(수)</td>
								</tr>
								<tr>
									<td>신규 회원</td>
									<td>4. 27.(월) ~ 4. 30.(목)</td>
								</tr>
							</tbody>
						</table>
					</body>
				</html>
				""");

		NoticeExtractionResult result = extractByRule(service, "5월 수영 회원 모집 안내", document);

		assertEquals(3, result.registrationPeriods().size());
		assertPeriod(result.registrationPeriods(), "기존 회원 및 추첨종목 모집기간", 4, 20, 4, 24);
		assertPeriod(result.registrationPeriods(), "아쿠아 잔여 모집기간", 4, 25, 4, 28);
		assertPeriod(result.registrationPeriods(), "신규 회원", 4, 27, 4, 30);
	}

	@Test
	void facilityScopedNoticeCandidatesIgnoreDeeperTabMenus() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<ul>
					<li>
						<a href="/fmcs/44" data-depth="2"><span>소사국민체육센터</span></a>
						<ul>
							<li><a href="/fmcs/44" class="menutype_modules_contents" data-depth="3"><span>시설안내</span></a></li>
							<li>
								<a href="/fmcs/225" data-depth="3"><span>수강신청안내</span></a>
								<ul>
									<li><a href="/fmcs/225" data-depth="4"><span>회원이용안내</span></a></li>
									<li><a href="/fmcs/226" data-depth="4"><span>환불/연기 안내</span></a></li>
								</ul>
							</li>
							<li>
								<a href="/fmcs/228" data-depth="3"><span>프로그램안내</span></a>
								<ul>
									<li><a href="/fmcs/228" data-depth="4"><span>수영장 프로그램</span></a></li>
									<li><a href="/fmcs/229" data-depth="4"><span>휘트니스 프로그램</span></a></li>
									<li><a href="/fmcs/230" data-depth="4"><span>체육관 프로그램</span></a></li>
								</ul>
							</li>
							<li><a href="/fmcs/312" class="menutype_modules_board" data-depth="3"><span>회원모집안내</span></a></li>
						</ul>
					</li>
				</ul>
				""", "https://www.best.or.kr/fmcs/44");
		Element facilityLink = document.selectFirst("a[href=/fmcs/44][data-depth=2]");
		Element scope = facilityLink.closest("li");
		Set<String> urls = new LinkedHashSet<>();

		addNoticeListCandidatesFrom(service, "https://www.best.or.kr/fmcs/44", scope, urls, 3);

		assertEquals(List.of(
				"https://www.best.or.kr/fmcs/225",
				"https://www.best.or.kr/fmcs/228",
				"https://www.best.or.kr/fmcs/312"
		), urls.stream().toList());
	}

	@Test
	void noticeListCandidatesSkipRentalAndBroadGuideLinks() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<nav>
					<a href="/course/sports/fmcs/12" data-depth="2">수강신청안내</a>
					<a href="/course/sports/fmcs/181" data-depth="2">중랑천체육시설 대관 이용안내</a>
					<a href="/course/sports/fmcs/321" data-depth="2">할인대상안내</a>
					<a href="/course/sports/fmcs/39" class="menutype_modules_board" data-depth="2">공지사항</a>
				</nav>
				""", "https://www.dfmc.kr:8443/course/sports/fmcs/121");
		Set<String> urls = new LinkedHashSet<>();

		addNoticeListCandidatesFrom(service, "https://www.dfmc.kr:8443/course/sports/fmcs/121", document, urls, null);

		assertEquals(List.of(
				"https://www.dfmc.kr:8443/course/sports/fmcs/12",
				"https://www.dfmc.kr:8443/course/sports/fmcs/39"
		), urls.stream().toList());
	}

	@Test
	void inlineNoticePagesSkipRentalTitles() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<head><title>중랑천체육시설 대관 이용안내 &lt; 대관예약 : 동대문구시설관리공단</title></head>
					<body>
						<p><strong>접수기간</strong> : 매월 17일 ~ 22일</p>
						<p>접수시간 : 평일 07:00 ~ 20:00</p>
					</body>
				</html>
				""");

		assertFalse(isInlineNoticePage(service, document));
	}

	@Test
	void noticeSourceVerificationIgnoresGlobalNavigationSignals() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<head><title>주거복지 정보 : 부천도시공사</title></head>
					<body>
						<nav class="gnb_wrap">
							<a href="/fmcs/312" class="menutype_modules_board">회원모집안내</a>
							<a href="/fmcs/271?center=BCS01">6월 수강신청</a>
							<a href="/fmcs/156" class="notice-board">공지사항</a>
						</nav>
						<div id="contents">
							<h2>주거복지 정보</h2>
							<table>
								<tbody>
									<tr><td>청년 주거복지 교육 안내</td></tr>
								</tbody>
							</table>
						</div>
					</body>
				</html>
				""", "https://www.best.or.kr/fmcs/670");

		assertFalse(isLikelyNoticeSource(service, document, "https://www.best.or.kr/fmcs/670"));
		assertEquals(0, detailCandidateCount(
				service,
				"https://www.best.or.kr/fmcs/44",
				"https://www.best.or.kr/fmcs/670",
				document
		));
	}

	@Test
	void noticeSourceVerificationUsesMainContentSignals() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<head><title>회원모집 안내</title></head>
					<body>
						<nav><a href="/unrelated">사이트 메뉴</a></nav>
						<main>
							<h2>회원모집 안내</h2>
							<div class="modules_board">
								<table>
									<tbody>
										<tr><td><a href="/notice/1">회원모집 공지</a></td></tr>
									</tbody>
								</table>
							</div>
						</main>
					</body>
				</html>
				""", "https://example.com/notices");

		assertTrue(isLikelyNoticeSource(service, document, "https://example.com/notices"));
	}

	@Test
	void detailNoticeCandidatesSupportFnViewOnclickLinks() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<form>
							<input type="hidden" name="bbsId" value="NOTICE">
						</form>
						<div id="content">
							<table>
								<tbody>
									<tr>
										<td>갈매멀티스포츠센터</td>
										<td>
											<a href="#none" onclick="fn_view(6593);">
												[갈매멀티스포츠센터] 2026년 6월 종목별 회원모집 안내
											</a>
										</td>
									</tr>
								</tbody>
							</table>
						</div>
					</body>
				</html>
				""", "https://www.guriuc.or.kr/sports/bbsArticle/list.do?bbsId=NOTICE");

		List<String> candidates = detailCandidateUrls(
				service,
				"https://www.guriuc.or.kr/sports/main/main.do",
				"https://www.guriuc.or.kr/sports/bbsArticle/list.do?bbsId=NOTICE",
				document
		);

		assertEquals(List.of("https://www.guriuc.or.kr/sports/bbsArticle/view.do?seq=6593&bbsId=NOTICE"), candidates);
	}

	@Test
	void detailNoticeCandidatesPreferRealContentAreaOverGenericPopupContent() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<head><title>공지사항 | 구리도시공사</title></head>
					<body>
						<div id="slideUpDiv">
							<div class="content">
								<img src="/sports/images/avatar.png" alt="mobile card">
							</div>
						</div>
						<div id="container">
							<form>
								<input type="hidden" name="bbsId" value="NOTICE">
							</form>
							<table>
								<caption>공지사항 게시판</caption>
								<tbody>
									<tr>
										<td>갈매멀티스포츠센터</td>
										<td>
											<a href="#none" onclick="fn_view(6593);">
												[갈매멀티스포츠센터] 2026년 6월 종목별 회원모집 안내
											</a>
										</td>
									</tr>
								</tbody>
							</table>
						</div>
					</body>
				</html>
				""", "https://www.guriuc.or.kr/sports/bbsArticle/list.do?bbsId=NOTICE");

		assertEquals(List.of("https://www.guriuc.or.kr/sports/bbsArticle/view.do?seq=6593&bbsId=NOTICE"), detailCandidateUrls(
				service,
				"https://www.guriuc.or.kr/sports/main/main.do",
				"https://www.guriuc.or.kr/sports/bbsArticle/list.do?bbsId=NOTICE",
				document
		));
	}

	@Test
	void noticeOcrImageSelectionPrioritizesTbodyAndSmartEditorImages() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<header>
							<img src="/sports/images/common/logo.svg" alt="site logo">
						</header>
						<div id="content">
							<img src="/sports/images/banner.png" alt="시설 안내 배너">
							<div class="tbody">
								<img src="/sports/images/avatar.png" alt="profile avatar">
								<img src="/sportsman/smartEditor/upload/notice-priority.jpg" alt="공지 안내문">
								<img src="/sports/uploads/notice-second.png" alt="본문 공지 이미지">
							</div>
							<img src="/sports/main/mberBarcodeImgView.do" alt="barcode image">
							<img src="/sportsman/smartEditor/upload/notice-third.jpg" alt="본문 외 업로드 이미지">
						</div>
					</body>
				</html>
				""", "https://www.guriuc.or.kr/sports/bbsArticle/view.do?seq=6790&bbsId=NOTICE");

		assertEquals(List.of(
				"https://www.guriuc.or.kr/sportsman/smartEditor/upload/notice-priority.jpg",
				"https://www.guriuc.or.kr/sports/uploads/notice-second.png",
				"https://www.guriuc.or.kr/sportsman/smartEditor/upload/notice-third.jpg",
				"https://www.guriuc.or.kr/sports/images/banner.png"
		), selectNoticeOcrImageUrls(service, document));
	}

	@Test
	void buildNoticeBodyTextUsesScopedMainContentInsteadOfWholeDocumentText() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<nav>
							<a href="/refund">환불안내</a>
						</nav>
						<div id="content">
							<p>갈매멀티스포츠센터 회원모집 안내</p>
							<p>접수기간 : 6월 15일(월) ~ 6월 19일(금)</p>
						</div>
						<footer>사이트맵</footer>
					</body>
				</html>
				""");

		String text = buildNoticeBodyText(service, "7월 회원 모집 안내", document);

		assertTrue(text.contains("접수기간 : 6월 15일(월) ~ 6월 19일(금)"));
		assertFalse(text.contains("환불안내"));
		assertFalse(text.contains("사이트맵"));
	}

	@Test
	void imageOcrIsNotCalledWhenHtmlRuleParsingAlreadyFindsPeriod() throws Exception {
		FakeNoticeImageOcrService ocrService = new FakeNoticeImageOcrService("매월 20일 ~ 25일");
		NoticeCrawlerService service = new NoticeCrawlerService(
				null,
				null,
				null,
				null,
				new ObjectMapper(),
				ocrService,
				false
		);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<p>수영 신규접수 : 매월 17일 ~ 22일</p>
						<img src="https://example.com/notice.png">
					</body>
				</html>
				""");

		NoticeTextExtractionOutcomeView outcome = extractNoticeDetail(
				service,
				"6월 수영 회원 모집 안내",
				"https://example.com/notices/1",
				"6월 수영 회원 모집 안내\n수영 신규접수 : 매월 17일 ~ 22일",
				document,
				List.of("https://example.com/notice.png")
		);

		assertTrue(outcome.result().hasPeriod());
		assertEquals(0, ocrService.callCount());
		assertFalse(outcome.rawText().contains("[OCR IMAGE TEXT]"));
	}

	@Test
	void imageOcrRetryUsesExtractedTextWhenHtmlParsingMissesPeriod() throws Exception {
		FakeNoticeImageOcrService ocrService = new FakeNoticeImageOcrService("수영 신규접수 : 매월 24일 ~ 말일");
		NoticeCrawlerService service = new NoticeCrawlerService(
				null,
				null,
				null,
				null,
				new ObjectMapper(),
				ocrService,
				false
		);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<p>갈매멀티스포츠센터 회원모집 안내</p>
						<img src="https://example.com/notice.png">
					</body>
				</html>
				""");

		NoticeTextExtractionOutcomeView outcome = extractNoticeDetail(
				service,
				"갈매멀티스포츠센터 6월 회원 모집 안내",
				"https://example.com/notices/2",
				"갈매멀티스포츠센터 6월 회원 모집 안내\n회원모집 안내",
				document,
				List.of("https://example.com/notice.png")
		);

		assertEquals(1, ocrService.callCount());
		assertTrue(outcome.rawText().contains("[OCR IMAGE TEXT]"));
		assertTrue(outcome.result().hasPeriod());
		assertEquals(1, outcome.result().registrationPeriods().size());
		assertEquals("매월 24일 ~ 말일", outcome.result().registrationPeriods().getFirst().periodText());
	}

	@Test
	void imageOcrRetryParsesTimedDateRangesByLineSegments() throws Exception {
		FakeNoticeImageOcrService ocrService = new FakeNoticeImageOcrService("""
				2026년 7월 종목별 강습반 모집 안내
				(재등록) 6월 15일(월) ~ 6월 19일(금) (06:00 ~ 22:00)
				(반변경) 6월 20일(토) ~ 6월 21일(일) (06:00 ~ 22:00)
				(신규추첨접수 온라인) 6월 22일(월) 08:00 ~ 6월 24일(수) 15:00
				환불안내
				(신규잔여석접수 온라인) 6월 27일(토) ~ 6월 30일(화) (06:00 ~ 22:00)
				""");
		NoticeCrawlerService service = new NoticeCrawlerService(
				null,
				null,
				null,
				null,
				new ObjectMapper(),
				ocrService,
				false
		);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<p>갈매멀티스포츠센터 회원모집 안내</p>
						<img src="https://example.com/notice.png">
					</body>
				</html>
				""");

		NoticeTextExtractionOutcomeView outcome = extractNoticeDetail(
				service,
				"갈매멀티스포츠센터 7월 회원 모집 안내",
				"https://example.com/notices/5",
				"갈매멀티스포츠센터 7월 회원 모집 안내\n회원모집 안내",
				document,
				List.of("https://example.com/notice.png")
		);

		assertTrue(outcome.result().hasPeriod());
		assertTrue(outcome.rawText().contains("6월 22일(월) ~ 6월 24일(수)"));
		assertPeriod(outcome.result().registrationPeriods(), "재등록", 6, 15, 6, 19);
		assertPeriod(outcome.result().registrationPeriods(), "반변경", 6, 20, 6, 21);
		assertPeriodExists(outcome.result().registrationPeriods(), 6, 22, 6, 24);
		assertPeriodExists(outcome.result().registrationPeriods(), 6, 27, 6, 30);
	}

	@Test
	void imageOcrRetryNormalizesDuplicateRangeFragmentsAndSuppressesMonthlyFalsePositive() throws Exception {
		FakeNoticeImageOcrService ocrService = new FakeNoticeImageOcrService("""
				2026년 7월 종목별 강습반 모집 안내
				(재등록) 6월 15일(월) ~ 6월 19일(금) (06:00 ~ 22:00)
				(반변경) 6월 20일(토) ~ 6월 21일(일) (06:00 ~ 22:00)
				(신규추첨접수 온라인) 6월 22일(월) 08:00 ~ 6월 24일(수) 15:00
				(신규결제) 당첨자 발표 후(17:00)~ 6월 26일(금) 22:00까지 (온라인 결제만 가능), 기한 내 미결제 시 당첨 취소
				(잔여 선착순) 6월 27일(토) ~ 6월 30일(화) (06:00 ~ 22:00)
				*접수방법 홈페이지 온라인 접수 또는 (반 변경)현장 방문접수
				※ 추첨 종목 신규접수는 매월 27일 접수
				""");
		NoticeCrawlerService service = new NoticeCrawlerService(
				null,
				null,
				null,
				null,
				new ObjectMapper(),
				ocrService,
				false
		);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<p>갈매멀티스포츠센터 회원모집 안내</p>
						<img src="https://example.com/notice.png">
					</body>
				</html>
				""");

		NoticeTextExtractionOutcomeView outcome = extractNoticeDetail(
				service,
				"갈매멀티스포츠센터 7월 회원 모집 안내",
				"https://example.com/notices/6",
				"갈매멀티스포츠센터 7월 회원 모집 안내\n회원모집 안내",
				document,
				List.of("https://example.com/notice.png")
		);

		assertTrue(outcome.result().hasPeriod());
		assertEquals(4, outcome.result().registrationPeriods().size());
		assertPeriod(outcome.result().registrationPeriods(), "재등록", 6, 15, 6, 19);
		assertPeriod(outcome.result().registrationPeriods(), "반변경", 6, 20, 6, 21);
		assertTrue(outcome.result().registrationPeriods().stream()
				.anyMatch(period -> "신규접수".equals(period.label())
						&& toSeoulDate(period.startsAt()).equals(LocalDate.of(2026, 6, 22))
						&& toSeoulDate(period.endsAt()).equals(LocalDate.of(2026, 6, 24))));
		assertTrue(outcome.result().registrationPeriods().stream()
				.anyMatch(period -> "신규접수".equals(period.label())
						&& toSeoulDate(period.startsAt()).equals(LocalDate.of(2026, 6, 27))
						&& toSeoulDate(period.endsAt()).equals(LocalDate.of(2026, 6, 30))));
		assertFalse(outcome.result().registrationPeriods().stream()
				.anyMatch(period -> period.startsAt().equals(period.endsAt())));
		assertFalse(outcome.result().registrationPeriods().stream()
				.anyMatch(period -> period.periodText() != null && period.periodText().contains("매월 27일")));
		assertFalse(outcome.result().registrationPeriods().stream()
				.anyMatch(period -> period.label() == null));
	}

	@Test
	void imageOcrFailureFallsBackToInitialHtmlResult() throws Exception {
		FakeNoticeImageOcrService ocrService = new FakeNoticeImageOcrService(new RuntimeException("tesseract missing"));
		NoticeCrawlerService service = new NoticeCrawlerService(
				null,
				null,
				null,
				null,
				new ObjectMapper(),
				ocrService,
				false
		);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<p>갈매멀티스포츠센터 회원모집 안내</p>
						<img src="https://example.com/notice.png">
					</body>
				</html>
				""");

		NoticeTextExtractionOutcomeView outcome = extractNoticeDetail(
				service,
				"갈매멀티스포츠센터 6월 회원 모집 안내",
				"https://example.com/notices/3",
				"갈매멀티스포츠센터 6월 회원 모집 안내\n회원모집 안내",
				document,
				List.of("https://example.com/notice.png")
		);

		assertEquals(1, ocrService.callCount());
		assertFalse(outcome.result().hasPeriod());
		assertFalse(outcome.rawText().contains("[OCR IMAGE TEXT]"));
	}

	@Test
	void emptyImageOcrTextKeepsInitialHtmlResult() throws Exception {
		FakeNoticeImageOcrService ocrService = new FakeNoticeImageOcrService("   ");
		NoticeCrawlerService service = new NoticeCrawlerService(
				null,
				null,
				null,
				null,
				new ObjectMapper(),
				ocrService,
				false
		);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<p>갈매멀티스포츠센터 회원모집 안내</p>
						<img src="https://example.com/notice.png">
					</body>
				</html>
				""");

		NoticeTextExtractionOutcomeView outcome = extractNoticeDetail(
				service,
				"갈매멀티스포츠센터 6월 회원 모집 안내",
				"https://example.com/notices/4",
				"갈매멀티스포츠센터 6월 회원 모집 안내\n회원모집 안내",
				document,
				List.of("https://example.com/notice.png")
		);

		assertEquals(1, ocrService.callCount());
		assertFalse(outcome.result().hasPeriod());
		assertFalse(outcome.rawText().contains("[OCR IMAGE TEXT]"));
	}

	@Test
	void resolvesFnViewDetailUrlFromPlaceholderAnchor() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<form>
							<input type="hidden" name="bbsId" value="NOTICE">
						</form>
						<a href="#none" onclick="fn_view(6593);">
							[갈매멀티스포츠센터] 2026년 6월 종목별 회원모집 안내
						</a>
					</body>
				</html>
				""", "https://www.guriuc.or.kr/sports/bbsArticle/list.do?bbsId=NOTICE");
		Element link = document.selectFirst("a[href]");

		String detailUrl = resolveDetailNoticeUrl(
				service,
				"https://www.guriuc.or.kr/sports/bbsArticle/list.do?bbsId=NOTICE",
				document,
				link
		);

		assertEquals("https://www.guriuc.or.kr/sports/bbsArticle/view.do?seq=6593&bbsId=NOTICE", detailUrl);
	}

	@Test
	void extractsMonthlyRegistrationPeriodFromInlinePageText() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<p class="indent1"><strong>접수기간</strong> : 매월 17일 ~ 22일</p>
						<p class="indent1">접수시간 : 평일 07:00 ~ 20:00</p>
					</body>
				</html>
				""");

		NoticeExtractionResult result = extractByRule(service, "동대문구민체육센터 수영 접수 안내", document);

		assertEquals(1, result.registrationPeriods().size());
		NoticeRegistrationPeriod period = result.registrationPeriods().getFirst();
		assertEquals("접수기간", period.label());
		assertEquals("매월 17일 ~ 22일", period.periodText());
		LocalDate[] expected = expectedMonthlyRange(17, 22);
		assertEquals(expected[0], toSeoulDate(period.startsAt()));
		assertEquals(expected[1], toSeoulDate(period.endsAt()));
	}

	@Test
	void extractsMonthlySingleDayAndMonthEndPeriodsFromInlinePageText() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<p><strong>재등록</strong></p>
						<p><strong>접수기간</strong> : 매월 17일 ~ 22일</p>
						<p><strong>반변경</strong> 접수기간 : 매월 23일</p>
						<p><strong>신규신청</strong> 접수기간 : 매월 24일~말일</p>
						<p>수영 신규접수 : 매월 25일 ~ 말일</p>
						<p>아쿠아로빅 신규접수 : 동대문구민 매월 25일 ~ 말일 / 타구민 매월 26일 ~ 말일</p>
					</body>
				</html>
				""");

		NoticeExtractionResult result = extractByRule(service, "동대문구민체육센터 수영 접수 안내", document);

		assertEquals(6, result.registrationPeriods().size());
		assertMonthlyPeriod(result.registrationPeriods(), "재등록", "매월 17일 ~ 22일", expectedMonthlyRange(17, 22));
		assertMonthlyPeriod(result.registrationPeriods(), "반변경", "매월 23일", expectedMonthlyRange(23, 23));
		assertMonthlyPeriod(result.registrationPeriods(), "신규신청", "매월 24일~말일", expectedMonthlyRangeToEnd(24));
		assertMonthlyPeriod(result.registrationPeriods(), "수영 신규접수", "매월 25일 ~ 말일", expectedMonthlyRangeToEnd(25));
		assertMonthlyPeriod(result.registrationPeriods(), "아쿠아로빅 신규접수", "매월 25일 ~ 말일", expectedMonthlyRangeToEnd(25));
		assertMonthlyPeriod(result.registrationPeriods(), "아쿠아로빅 신규접수", "매월 26일 ~ 말일", expectedMonthlyRangeToEnd(26));
	}

	@Test
	void extractsExplicitMonthPeriodsWithOmittedEndMonthAndSkipsDrawingDay() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<table>
					<tbody>
						<tr>
							<td>회원 구분</td>
							<td>접수기간</td>
						</tr>
						<tr>
							<td>기존회원(재등록) 초급수영 접수</td>
							<td>5월 20일 ~ 24일</td>
						</tr>
						<tr>
							<td>반변경 및 초급수영추첨</td>
							<td>5월 26일 반변경</td>
						</tr>
						<tr>
							<td>아쿠아 잔여자리 접수및 추첨시간</td>
							<td>
								<p>5월 26일~27일</p>
								<p>5월 28일 10시 추첨</p>
							</td>
						</tr>
						<tr>
							<td>신규회원접수</td>
							<td>5월 27일 ~ 말일</td>
						</tr>
					</tbody>
				</table>
				""");

		NoticeExtractionResult result = extractByRule(
				service,
				"2026년 6월 오정레포츠센터 회원모집 안내",
				document
		);

		assertEquals(4, result.registrationPeriods().size());
		assertPeriod(result.registrationPeriods(), "재등록", 5, 20, 5, 24);
		assertPeriod(result.registrationPeriods(), "반변경", 5, 26, 5, 26);
		assertPeriod(result.registrationPeriods(), "아쿠아 잔여자리 접수및 추첨시간", 5, 26, 5, 27);
		assertPeriod(result.registrationPeriods(), "신규 회원", 5, 27, 5, 31);
		assertFalse(result.registrationPeriods().stream()
				.anyMatch(period -> "5월 28일".equals(period.periodText())));
	}

	@Test
	void combinesTableAndContentBlocksWhileSkippingRefundPeriods() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<table class="indent1">
							<caption>성동구민종합센터 스포츠강좌 환불금액</caption>
							<thead>
								<tr>
									<th>체육센터 귀책사유</th>
									<th>소비자 귀책사유</th>
								</tr>
							</thead>
							<tbody>
								<tr>
									<td>
										<ul>
											<li>개강(매월 1일) 이전 : 전액 환불</li>
											<li>개강(매월 1일) 이후 : 환불일까지 이용일수 해당금액 공제한 금액 환불</li>
										</ul>
									</td>
									<td>
										<ul>
											<li>개강(첫수업일)이전 : 전액 환불</li>
											<li>개강(첫수업일)이후 : 수강료 10%(1개월단위) 공제 + 수업일수 일할 계산 후 환불</li>
										</ul>
									</td>
								</tr>
							</tbody>
						</table>
						<h4>접수기간</h4>
						<ul class="indent1">
							<li>재등록회원: 매월 15일 ~ 20일(07시부터 선착순 온라인 및 방문접수)</li>
							<li>성동구민우선등록회원: 매월 21일 ~ 22일(08시 30분부터 선착순 방문 및 온라인 접수)</li>
							<li>일반등록회원(타구민포함): 매월 23일 ~ 익월 7일(08시 30분부터 선착순 온라인 및 방문접수)</li>
						</ul>
					</body>
				</html>
				""");

		NoticeExtractionResult result = extractByRule(service, "성동구립용답체육센터 스포츠강좌 수강 안내", document);

		assertEquals(3, result.registrationPeriods().size());
		assertMonthlyPeriod(result.registrationPeriods(), "재등록회원", "매월 15일 ~ 20일", expectedMonthlyRange(15, 20));
		assertMonthlyPeriod(result.registrationPeriods(), "성동구민 우선등록회원", "매월 21일 ~ 22일", expectedMonthlyRange(21, 22));
		assertMonthlyPeriod(result.registrationPeriods(), "일반등록회원", "매월 23일 ~ 익월 7일", expectedMonthlyNextMonthRange(23, 7));
	}

	@Test
	void tableDateCellsUseColumnHeaderInsteadOfSiblingCellText() throws Exception {
		NoticeCrawlerService service = new NoticeCrawlerService(null, null, null, null, new ObjectMapper(), false);
		Document document = Jsoup.parse("""
				<html>
					<body>
						<table>
							<thead>
								<tr>
									<th>접수기간</th>
									<th>접수시간</th>
								</tr>
							</thead>
							<tbody>
								<tr>
									<td>매월 15일 ~ 20일</td>
									<td>07시부터 선착순 온라인 및 방문접수</td>
								</tr>
							</tbody>
						</table>
					</body>
				</html>
				""");

		NoticeExtractionResult result = extractByRule(service, "수영 접수 안내", document);

		assertEquals(1, result.registrationPeriods().size());
		NoticeRegistrationPeriod period = result.registrationPeriods().getFirst();
		assertEquals("접수기간", period.label());
		assertEquals("매월 15일 ~ 20일", period.periodText());
	}

	private NoticeExtractionResult extractByRule(NoticeCrawlerService service, String title, Document document) throws Exception {
		Method method = NoticeCrawlerService.class.getDeclaredMethod(
				"extractByRule",
				String.class,
				String.class,
				String.class,
				Document.class
		);
		method.setAccessible(true);
		try {
			return (NoticeExtractionResult) method.invoke(
					service,
					title,
					"https://example.com/notices/1",
					title + "\n" + document.text(),
					document
			);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof Exception wrapped) {
				throw wrapped;
			}
			throw exception;
		}
	}

	private void addNoticeListCandidatesFrom(
			NoticeCrawlerService service,
			String rootUrl,
			Element scope,
			Set<String> urls,
			Integer maxDepth
	) throws Exception {
		Method method = NoticeCrawlerService.class.getDeclaredMethod(
				"addNoticeListCandidatesFrom",
				String.class,
				Element.class,
				Set.class,
				Integer.class
		);
		method.setAccessible(true);
		method.invoke(service, rootUrl, scope, urls, maxDepth);
	}

	private boolean isInlineNoticePage(NoticeCrawlerService service, Document document) throws Exception {
		Method method = NoticeCrawlerService.class.getDeclaredMethod("isInlineNoticePage", Document.class);
		method.setAccessible(true);
		return (boolean) method.invoke(service, document);
	}

	private boolean isLikelyNoticeSource(
			NoticeCrawlerService service,
			Document document,
			String sourceUrl
	) throws Exception {
		Method method = NoticeCrawlerService.class.getDeclaredMethod(
				"isLikelyNoticeSource",
				Document.class,
				String.class
		);
		method.setAccessible(true);
		return (boolean) method.invoke(service, document, sourceUrl);
	}

	private int detailCandidateCount(
			NoticeCrawlerService service,
			String homepageUrl,
			String noticeListUrl,
			Document document
	) throws Exception {
		Method method = NoticeCrawlerService.class.getDeclaredMethod(
				"discoverDetailNoticeUrls",
				String.class,
				String.class,
				Document.class
		);
		method.setAccessible(true);
		List<?> candidates = (List<?>) method.invoke(service, homepageUrl, noticeListUrl, document);
		return candidates.size();
	}

	private List<String> detailCandidateUrls(
			NoticeCrawlerService service,
			String homepageUrl,
			String noticeListUrl,
			Document document
	) throws Exception {
		Method method = NoticeCrawlerService.class.getDeclaredMethod(
				"discoverDetailNoticeUrls",
				String.class,
				String.class,
				Document.class
		);
		method.setAccessible(true);
		List<?> candidates = (List<?>) method.invoke(service, homepageUrl, noticeListUrl, document);
		List<String> urls = new java.util.ArrayList<>();
		for (Object candidate : candidates) {
			Method urlMethod = candidate.getClass().getDeclaredMethod("url");
			urlMethod.setAccessible(true);
			urls.add((String) urlMethod.invoke(candidate));
		}
		return urls;
	}

	private String resolveDetailNoticeUrl(
			NoticeCrawlerService service,
			String noticeListUrl,
			Document document,
			Element link
	) throws Exception {
		Method method = NoticeCrawlerService.class.getDeclaredMethod(
				"resolveDetailNoticeUrl",
				String.class,
				Document.class,
				Element.class
		);
		method.setAccessible(true);
		return (String) method.invoke(service, noticeListUrl, document, link);
	}

	private NoticeTextExtractionOutcomeView extractNoticeDetail(
			NoticeCrawlerService service,
			String title,
			String url,
			String text,
			Document document,
			List<String> imageUrls
	) throws Exception {
		Method method = NoticeCrawlerService.class.getDeclaredMethod(
				"extractNoticeDetail",
				String.class,
				String.class,
				String.class,
				Document.class,
				List.class
		);
		method.setAccessible(true);
		Object outcome = method.invoke(service, title, url, text, document, imageUrls);
		Method rawTextMethod = outcome.getClass().getDeclaredMethod("rawText");
		Method resultMethod = outcome.getClass().getDeclaredMethod("result");
		rawTextMethod.setAccessible(true);
		resultMethod.setAccessible(true);
		return new NoticeTextExtractionOutcomeView(
				(String) rawTextMethod.invoke(outcome),
				(NoticeExtractionResult) resultMethod.invoke(outcome)
		);
	}

	private String buildNoticeBodyText(
			NoticeCrawlerService service,
			String title,
			Document document
	) throws Exception {
		Method method = NoticeCrawlerService.class.getDeclaredMethod(
				"buildNoticeBodyText",
				String.class,
				Document.class
		);
		method.setAccessible(true);
		return (String) method.invoke(service, title, document);
	}

	@SuppressWarnings("unchecked")
	private List<String> selectNoticeOcrImageUrls(
			NoticeCrawlerService service,
			Document document
	) throws Exception {
		Method method = NoticeCrawlerService.class.getDeclaredMethod(
				"selectNoticeOcrImageUrls",
				Document.class
		);
		method.setAccessible(true);
		return (List<String>) method.invoke(service, document);
	}

	private void assertPeriod(
			List<NoticeRegistrationPeriod> periods,
			String label,
			int startMonth,
			int startDay,
			int endMonth,
			int endDay
	) {
		NoticeRegistrationPeriod period = periods.stream()
				.filter(candidate -> label.equals(candidate.label()))
				.findFirst()
				.orElse(null);
		assertNotNull(period, "Expected period label: " + label);
		int year = LocalDate.now(SEOUL).getYear();
		assertEquals(LocalDate.of(year, startMonth, startDay), toSeoulDate(period.startsAt()));
		assertEquals(LocalDate.of(year, endMonth, endDay), toSeoulDate(period.endsAt()));
	}

	private void assertPeriodExists(
			List<NoticeRegistrationPeriod> periods,
			int startMonth,
			int startDay,
			int endMonth,
			int endDay
	) {
		int year = LocalDate.now(SEOUL).getYear();
		NoticeRegistrationPeriod period = periods.stream()
				.filter(candidate -> LocalDate.of(year, startMonth, startDay).equals(toSeoulDate(candidate.startsAt()))
						&& LocalDate.of(year, endMonth, endDay).equals(toSeoulDate(candidate.endsAt())))
				.findFirst()
				.orElse(null);
		assertNotNull(period, "Expected period range: " + startMonth + "/" + startDay + " ~ " + endMonth + "/" + endDay);
	}

	private LocalDate toSeoulDate(Instant instant) {
		return instant.atZone(SEOUL).toLocalDate();
	}

	private LocalDate[] expectedMonthlyRange(int startDay, int endDay) {
		LocalDate today = LocalDate.now(SEOUL);
		java.time.YearMonth month = java.time.YearMonth.from(today);
		LocalDate startsAt = month.atDay(startDay);
		LocalDate endsAt = endDay >= startDay ? month.atDay(endDay) : month.plusMonths(1).atDay(endDay);
		if (endsAt.isBefore(today)) {
			java.time.YearMonth nextMonth = month.plusMonths(1);
			startsAt = nextMonth.atDay(startDay);
			endsAt = endDay >= startDay ? nextMonth.atDay(endDay) : nextMonth.plusMonths(1).atDay(endDay);
		}
		return new LocalDate[] {startsAt, endsAt};
	}

	private LocalDate[] expectedMonthlyRangeToEnd(int startDay) {
		LocalDate today = LocalDate.now(SEOUL);
		java.time.YearMonth month = java.time.YearMonth.from(today);
		LocalDate startsAt = month.atDay(startDay);
		LocalDate endsAt = month.atEndOfMonth();
		if (endsAt.isBefore(today)) {
			java.time.YearMonth nextMonth = month.plusMonths(1);
			startsAt = nextMonth.atDay(startDay);
			endsAt = nextMonth.atEndOfMonth();
		}
		return new LocalDate[] {startsAt, endsAt};
	}

	private LocalDate[] expectedMonthlyNextMonthRange(int startDay, int nextMonthEndDay) {
		LocalDate today = LocalDate.now(SEOUL);
		java.time.YearMonth month = java.time.YearMonth.from(today);
		LocalDate startsAt = month.atDay(startDay);
		LocalDate endsAt = month.plusMonths(1).atDay(nextMonthEndDay);
		if (endsAt.isBefore(today)) {
			java.time.YearMonth nextMonth = month.plusMonths(1);
			startsAt = nextMonth.atDay(startDay);
			endsAt = nextMonth.plusMonths(1).atDay(nextMonthEndDay);
		}
		return new LocalDate[] {startsAt, endsAt};
	}

	private void assertMonthlyPeriod(
			List<NoticeRegistrationPeriod> periods,
			String label,
			String periodText,
			LocalDate[] expected
	) {
		NoticeRegistrationPeriod period = periods.stream()
				.filter(candidate -> label.equals(candidate.label()) && periodText.equals(candidate.periodText()))
				.findFirst()
				.orElse(null);
		assertNotNull(period, "Expected period label/text: " + label + " / " + periodText);
		assertEquals(expected[0], toSeoulDate(period.startsAt()));
		assertEquals(expected[1], toSeoulDate(period.endsAt()));
	}

	private record NoticeTextExtractionOutcomeView(String rawText, NoticeExtractionResult result) {
	}

	private static final class FakeNoticeImageOcrService implements NoticeImageOcrService {
		private final String text;
		private final RuntimeException exception;
		private int callCount;

		private FakeNoticeImageOcrService(String text) {
			this.text = text;
			this.exception = null;
		}

		private FakeNoticeImageOcrService(RuntimeException exception) {
			this.text = null;
			this.exception = exception;
		}

		@Override
		public NoticeImageOcrResult extractText(List<String> imageUrls) {
			callCount++;
			if (exception != null) {
				throw exception;
			}
			return new NoticeImageOcrResult(text, imageUrls.size(), text == null || text.isBlank() ? 0 : 1, "fake");
		}

		private int callCount() {
			return callCount;
		}
	}
}
