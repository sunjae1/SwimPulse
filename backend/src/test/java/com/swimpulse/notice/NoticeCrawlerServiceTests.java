package com.swimpulse.notice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
