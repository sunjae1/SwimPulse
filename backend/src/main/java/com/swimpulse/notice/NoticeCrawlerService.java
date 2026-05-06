package com.swimpulse.notice;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.NotFoundException;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import java.io.IOException;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoticeCrawlerService {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final Pattern PERIOD = Pattern.compile("(\\d{1,2})\\s*[./월]\\s*(\\d{1,2})\\s*[일.]?\\s*(?:\\([^)]*\\))?\\s*[~\\-–]\\s*(\\d{1,2})\\s*[./월]\\s*(\\d{1,2})\\s*[일.]?\\s*(?:\\([^)]*\\))?");
	private static final Pattern DAY_ONLY_PERIOD = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*\\.\\s*(?:\\([^)]*\\))?\\s*[~\\-–]\\s*(\\d{1,2})\\s*\\.\\s*(?:\\([^)]*\\))?(?!\\s*\\d)");
	private static final List<String> NOTICE_LIST_KEYWORDS = List.of("공지", "회원모집", "회원모집안내", "모집안내", "수강", "접수", "프로그램", "안내", "board", "notice");
	private static final List<String> DETAIL_KEYWORDS = List.of("수강", "회원", "접수", "모집", "등록", "수영", "강습");
	private static final List<PeriodLabel> PERIOD_LABELS = List.of(
			new PeriodLabel("신규회원모집", "신규회원모집"),
			new PeriodLabel("신규회원", "신규 회원"),
			new PeriodLabel("기존회원및추첨종목모집기간", "기존 회원 및 추첨종목 모집기간"),
			new PeriodLabel("기존회원재등록", "기존회원 재등록"),
			new PeriodLabel("수영초급반추첨모집", "수영초급반 추첨모집"),
			new PeriodLabel("아쿠아잔여모집기간", "아쿠아 잔여 모집기간"),
			new PeriodLabel("아쿠아프로그램잔여자리모집", "아쿠아프로그램 잔여자리 모집"),
			new PeriodLabel("프로그램반변경", "프로그램 반변경"),
			new PeriodLabel("특화반", "특화반")
	);
	private static final int MAX_NOTICE_LIST_URLS = 6;
	private static final int MAX_DETAIL_URLS_PER_LIST = 10;

	private final PoolRepository poolRepository;
	private final PoolNoticeRepository noticeRepository;
	private final PoolNoticeSourceRepository sourceRepository;
	private final OpenAiNoticeExtractionClient openAiNoticeExtractionClient;

	public NoticeCrawlerService(
			PoolRepository poolRepository,
			PoolNoticeRepository noticeRepository,
			PoolNoticeSourceRepository sourceRepository,
			OpenAiNoticeExtractionClient openAiNoticeExtractionClient
	) {
		this.poolRepository = poolRepository;
		this.noticeRepository = noticeRepository;
		this.sourceRepository = sourceRepository;
		this.openAiNoticeExtractionClient = openAiNoticeExtractionClient;
	}

	@Transactional
	public NoticeScanResponse scan(Long poolId) {
		List<String> trace = new ArrayList<>();
		Pool pool = poolRepository.findById(poolId)
				.orElseThrow(() -> new NotFoundException("Pool not found: " + poolId));
		String homepageUrl = pool.getHomepageUrl();
		if (!hasText(homepageUrl)) {
			throw new BadRequestException("Pool homepageUrl is empty.");
		}

		trace.add("홈페이지에서 시설명 메뉴 영역을 먼저 탐색합니다: " + homepageUrl);
		List<String> noticeListUrls = discoverFacilityScopedNoticeListUrls(homepageUrl, pool.getName(), trace);
		if (noticeListUrls.isEmpty()) {
			trace.add("시설명 메뉴 영역에서 공지 목록을 못 찾아 홈페이지 전체 링크를 탐색합니다.");
			noticeListUrls = discoverNoticeListUrls(homepageUrl, trace, "홈페이지");
		}
		List<NoticeDetailCandidate> detailCandidates = new ArrayList<>();
		collectDetailCandidates(pool, homepageUrl, noticeListUrls, detailCandidates, trace);

		if (detailCandidates.isEmpty()) {
			trace.add("상세 공지 후보가 없어 시설명 링크를 루트로 바꾸는 fallback을 실행합니다.");
			List<String> facilityPageUrls = discoverFacilityPageUrls(homepageUrl, pool.getName(), trace);
			for (String facilityPageUrl : facilityPageUrls) {
				trace.add("fallback 루트 탐색: " + facilityPageUrl);
				List<String> fallbackNoticeListUrls = discoverFacilityScopedNoticeListUrls(facilityPageUrl, pool.getName(), trace);
				if (fallbackNoticeListUrls.isEmpty()) {
					fallbackNoticeListUrls = discoverNoticeListUrls(facilityPageUrl, trace, "fallback 시설 페이지");
				}
				collectDetailCandidates(pool, facilityPageUrl, fallbackNoticeListUrls, detailCandidates, trace);
				if (!detailCandidates.isEmpty()) {
					break;
				}
			}
		}

		List<PoolNoticeResponse> notices = new ArrayList<>();
		for (NoticeDetailCandidate candidate : detailCandidates) {
			noticeRepository.findByUrl(candidate.url()).ifPresentOrElse(
					existing -> {
						trace.add("기존 저장 공지 재사용: " + candidate.title() + " -> " + candidate.url());
						notices.add(PoolNoticeResponse.from(existing));
					},
					() -> {
						trace.add("상세 공지 본문 분석: " + candidate.title() + " -> " + candidate.url());
						notices.add(PoolNoticeResponse.from(scanNoticeDetail(pool, candidate)));
					}
			);
		}
		String message = notices.isEmpty() ? "No detail notice candidates found." : "Notice scan completed.";
		return new NoticeScanResponse(pool.getId(), pool.getName(), homepageUrl, detailCandidates.size(), notices, message, trace);
	}

	private void collectDetailCandidates(
			Pool pool,
			String rootUrl,
			List<String> noticeListUrls,
			List<NoticeDetailCandidate> detailCandidates,
			List<String> trace
	) {
		if (noticeListUrls.isEmpty()) {
			trace.add("공지 목록 후보 URL이 없습니다: " + rootUrl);
			return;
		}
		for (String noticeListUrl : noticeListUrls) {
			PoolNoticeSource source = sourceRepository.findByPoolAndSourceUrl(pool, noticeListUrl)
					.orElseGet(() -> sourceRepository.save(new PoolNoticeSource(pool, noticeListUrl, NoticeSourceType.NOTICE_PAGE)));
			try {
				List<NoticeDetailCandidate> found = discoverDetailNoticeUrls(rootUrl, noticeListUrl);
				detailCandidates.addAll(found);
				trace.add("공지 목록 페이지 분석 완료: " + noticeListUrl + " / 상세 후보 " + found.size() + "개");
				source.markScanned(NoticeSourceStatus.ACTIVE);
			} catch (RuntimeException exception) {
				source.markScanned(NoticeSourceStatus.FAILED);
				trace.add("공지 목록 페이지 분석 실패: " + noticeListUrl + " / " + exception.getMessage());
			}
		}
	}

	private List<String> discoverFacilityScopedNoticeListUrls(String homepageUrl, String poolName, List<String> trace) {
		try {
			Document document = fetch(homepageUrl);
			List<Element> facilityLinks = findFacilityLinks(document, poolName);
			if (facilityLinks.isEmpty()) {
				trace.add("시설명과 일치하는 메뉴 링크를 찾지 못했습니다: " + poolName);
				return List.of();
			}

			Set<String> urls = new LinkedHashSet<>();
			for (Element facilityLink : facilityLinks) {
				String facilityUrl = facilityLink.absUrl("href");
				trace.add("시설명 메뉴 발견: " + firstText(facilityLink.text(), "(텍스트 없음)") + " -> " + facilityUrl);
				Element scope = facilityLink.closest("li");
				if (scope == null) {
					continue;
				}
				addNoticeListCandidatesFrom(homepageUrl, scope, urls);
				if (urls.size() >= MAX_NOTICE_LIST_URLS) {
					break;
				}
			}
			trace.add("시설명 메뉴 영역의 공지 목록 후보 " + urls.size() + "개 발견");
			return urls.stream().limit(MAX_NOTICE_LIST_URLS).toList();
		} catch (RuntimeException exception) {
			trace.add("시설명 메뉴 영역 탐색 실패: " + exception.getMessage());
			return List.of();
		}
	}

	private List<String> discoverNoticeListUrls(String rootUrl, List<String> trace, String contextLabel) {
		try {
			Document document = fetch(rootUrl);
			Set<String> urls = new LinkedHashSet<>();
			addNoticeListCandidatesFrom(rootUrl, document, urls);
			trace.add(contextLabel + " 전체 링크에서 공지 목록 후보 " + urls.size() + "개 발견");
			return urls.stream().limit(MAX_NOTICE_LIST_URLS).toList();
		} catch (RuntimeException exception) {
			trace.add(contextLabel + " 전체 링크 탐색 실패: " + exception.getMessage());
			return List.of();
		}
	}

	private List<String> discoverFacilityPageUrls(String homepageUrl, String poolName, List<String> trace) {
		try {
			Document document = fetch(homepageUrl);
			Set<String> urls = new LinkedHashSet<>();
			for (Element link : findFacilityLinks(document, poolName)) {
				String absoluteUrl = link.absUrl("href");
				if (hasText(absoluteUrl) && sameHost(homepageUrl, absoluteUrl)) {
					urls.add(absoluteUrl);
				}
				if (urls.size() >= 3) {
					break;
				}
			}
			trace.add("fallback 시설 페이지 후보 " + urls.size() + "개 발견");
			return urls.stream().toList();
		} catch (RuntimeException exception) {
			trace.add("fallback 시설 페이지 탐색 실패: " + exception.getMessage());
			return List.of();
		}
	}

	private List<Element> findFacilityLinks(Document document, String poolName) {
		String normalizedPoolName = normalizeFacilityName(poolName);
		if (normalizedPoolName.length() < 3) {
			return List.of();
		}
		List<Element> links = new ArrayList<>();
		for (Element link : document.select("a[href]")) {
			String linkText = normalizeFacilityName(link.text());
			if (linkText.length() < 3) {
				continue;
			}
			if (linkText.equals(normalizedPoolName) || linkText.contains(normalizedPoolName) || normalizedPoolName.contains(linkText)) {
				links.add(link);
			}
			if (links.size() >= 5) {
				break;
			}
		}
		return links;
	}

	private void addNoticeListCandidatesFrom(String rootUrl, Element scope, Set<String> urls) {
		for (Element link : scope.select("a[href]")) {
			if (isNoticeListCandidate(link)) {
				String absoluteUrl = link.absUrl("href");
				if (hasText(absoluteUrl) && sameHost(rootUrl, absoluteUrl)) {
					urls.add(absoluteUrl);
				}
			}
		}
	}

	private boolean isNoticeListCandidate(Element link) {
		String haystack = normalizeForSearch(link.text() + " " + link.attr("href") + " " + link.attr("class") + " " + link.attr("id"));
		return containsAny(haystack, NOTICE_LIST_KEYWORDS);
	}

	private List<NoticeDetailCandidate> discoverDetailNoticeUrls(String homepageUrl, String noticeListUrl) {
		Document document = fetch(noticeListUrl);
		Set<NoticeDetailCandidate> candidates = new LinkedHashSet<>();
		for (Element link : document.select("a[href]")) {
			String title = firstText(link.text(), link.attr("title"));
			String absoluteUrl = link.absUrl("href");
			if (!hasText(title) || !hasText(absoluteUrl) || !sameHost(homepageUrl, absoluteUrl)) {
				continue;
			}
			if (isDetailNoticeCandidate(title, link.attr("href"))) {
				candidates.add(new NoticeDetailCandidate(absoluteUrl, title));
			}
			if (candidates.size() >= MAX_DETAIL_URLS_PER_LIST) {
				break;
			}
		}
		return candidates.stream().toList();
	}

	private boolean isDetailNoticeCandidate(String anchorText, String href) {
		String haystack = normalizeForSearch(anchorText + " " + href);
		return hasMonthKeyword(haystack) && containsAny(haystack, DETAIL_KEYWORDS);
	}

	private PoolNotice scanNoticeDetail(Pool pool, NoticeDetailCandidate candidate) {
		try {
			Document document = fetch(candidate.url());
			String title = firstText(candidate.title(), firstText(document.title(), pool.getName() + " 공지"));
			String text = title + "\n" + document.text();
			List<String> imageUrls = document.select("img[src]")
					.stream()
					.map(image -> image.absUrl("src"))
					.filter(this::hasText)
					.limit(5)
					.toList();
			NoticeExtractionResult result = extractByRule(title, candidate.url(), text, document);
			if (!result.hasPeriod() && openAiNoticeExtractionClient.isConfigured()) {
				result = openAiNoticeExtractionClient.extract(title, candidate.url(), text, imageUrls);
			}
			NoticeExtractionStatus status = result.hasPeriod() && result.confidence() >= 0.65
					? NoticeExtractionStatus.EXTRACTED
					: NoticeExtractionStatus.LINK_ONLY;
			return noticeRepository.save(new PoolNotice(
					pool,
					firstText(result.title(), title),
					candidate.url(),
					truncate(text, 20_000),
					status,
					result.confidence(),
					result.registrationStartsAt(),
					result.registrationEndsAt(),
					truncate(result.reason(), 500)
			));
		} catch (RuntimeException exception) {
			return noticeRepository.save(new PoolNotice(
					pool,
					firstText(candidate.title(), pool.getName() + " 공지 확인 필요"),
					candidate.url(),
					null,
					NoticeExtractionStatus.FAILED,
					0.0,
					null,
					null,
					truncate(exception.getMessage(), 500)
			));
		}
	}

	private NoticeExtractionResult extractByRule(String title, String url, String text, Document document) {
		String haystack = normalizeForSearch(title + " " + text);
		if (!containsAny(haystack, DETAIL_KEYWORDS)) {
			return new NoticeExtractionResult(title, null, null, 0.2, "Registration keywords not found.", url);
		}
		List<MatchedPeriod> matchedPeriods = findTableMatchedPeriods(document);
		if (matchedPeriods.isEmpty()) {
			matchedPeriods = findMatchedPeriods(text);
		}
		if (matchedPeriods.isEmpty()) {
			return new NoticeExtractionResult(title, null, null, 0.45, "Period pattern not found.", url);
		}
		MatchedPeriod selected = selectRepresentativePeriod(matchedPeriods);
		return new NoticeExtractionResult(
				title,
				selected.startsAt().atStartOfDay(SEOUL).toInstant(),
				selected.endsAt().plusDays(1).atStartOfDay(SEOUL).minusSeconds(1).toInstant(),
				selected.label() == null ? 0.75 : 0.82,
				buildPeriodReason(matchedPeriods, selected),
				url
		);
	}

	private List<MatchedPeriod> findTableMatchedPeriods(Document document) {
		List<MatchedPeriod> periods = new ArrayList<>();
		for (Element row : document.select("table tr")) {
			List<Element> cells = row.select("> th, > td");
			if (cells.size() < 2) {
				continue;
			}
			for (int index = 0; index < cells.size(); index++) {
				String cellText = normalizeCellText(cells.get(index).text());
				if (!hasText(cellText)) {
					continue;
				}
				periods.addAll(findMatchedPeriodsInValue(
						cellText,
						resolveRowLabel(cells, index),
						"table row"
				));
				periods.addAll(findDayOnlyPeriodsInValue(
						cellText,
						resolveRowLabel(cells, index),
						inferMonthFromRow(row.text()),
						"table row"
				));
			}
		}
		return deduplicatePeriods(periods);
	}

	private List<MatchedPeriod> findMatchedPeriods(String text) {
		return findMatchedPeriodsInValue(text, null, "text");
	}

	private List<MatchedPeriod> findMatchedPeriodsInValue(String text, String label, String source) {
		int year = LocalDate.now(SEOUL).getYear();
		List<MatchedPeriod> periods = new ArrayList<>();
		Matcher matcher = PERIOD.matcher(text);
		while (matcher.find()) {
			try {
				LocalDate startsAt = LocalDate.of(year, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
				LocalDate endsAt = LocalDate.of(year, Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
				if (endsAt.isBefore(startsAt)) {
					endsAt = endsAt.plusYears(1);
				}
				periods.add(new MatchedPeriod(
						startsAt,
						endsAt,
						firstText(label, findPeriodLabel(text, matcher.start(), matcher.end())),
						text.substring(matcher.start(), matcher.end()).replaceAll("\\s+", " ").trim(),
						source
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed date fragments that merely look like registration periods.
			}
		}
		return periods;
	}

	private List<MatchedPeriod> findDayOnlyPeriodsInValue(String text, String label, Integer month, String source) {
		if (month == null || !containsAny(normalizeForSearch(text), List.of("모집", "접수", "기간", "회원", "수강"))) {
			return List.of();
		}
		int year = LocalDate.now(SEOUL).getYear();
		List<MatchedPeriod> periods = new ArrayList<>();
		Matcher matcher = DAY_ONLY_PERIOD.matcher(text);
		while (matcher.find()) {
			try {
				LocalDate startsAt = LocalDate.of(year, month, Integer.parseInt(matcher.group(1)));
				LocalDate endsAt = LocalDate.of(year, month, Integer.parseInt(matcher.group(2)));
				if (endsAt.isBefore(startsAt)) {
					endsAt = endsAt.plusMonths(1);
				}
				periods.add(new MatchedPeriod(
						startsAt,
						endsAt,
						firstText(label, findPeriodLabel(text, matcher.start(), matcher.end())),
						text.substring(matcher.start(), matcher.end()).replaceAll("\\s+", " ").trim(),
						source
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed date fragments that merely look like registration periods.
			}
		}
		return periods;
	}

	private String resolveRowLabel(List<Element> cells, int periodCellIndex) {
		String rowText = cells.stream()
				.map(Element::text)
				.collect(Collectors.joining(" "));
		String knownLabel = findPeriodLabel(rowText, 0, rowText.length());
		if (hasText(knownLabel)) {
			return knownLabel;
		}
		for (int index = 0; index < cells.size(); index++) {
			if (index == periodCellIndex) {
				continue;
			}
			String cellText = normalizeCellText(cells.get(index).text());
			if (hasText(cellText) && !PERIOD.matcher(cellText).find()) {
				return truncate(cellText, 80);
			}
		}
		return null;
	}

	private Integer inferMonthFromRow(String rowText) {
		Matcher matcher = PERIOD.matcher(rowText);
		if (matcher.find()) {
			return Integer.parseInt(matcher.group(1));
		}
		Matcher monthMatcher = Pattern.compile("(\\d{1,2})\\s*월").matcher(rowText);
		if (monthMatcher.find()) {
			return Integer.parseInt(monthMatcher.group(1));
		}
		return null;
	}

	private List<MatchedPeriod> deduplicatePeriods(List<MatchedPeriod> periods) {
		Set<String> seen = new LinkedHashSet<>();
		List<MatchedPeriod> unique = new ArrayList<>();
		for (MatchedPeriod period : periods) {
			String key = period.startsAt() + "|" + period.endsAt() + "|" + normalizeForSearch(period.label());
			if (seen.add(key)) {
				unique.add(period);
			}
		}
		return unique;
	}

	private MatchedPeriod selectRepresentativePeriod(List<MatchedPeriod> matchedPeriods) {
		for (PeriodLabel label : PERIOD_LABELS) {
			for (MatchedPeriod period : matchedPeriods) {
				if (hasText(period.label()) && normalizeForSearch(period.label()).contains(label.normalized())) {
					return period;
				}
			}
		}
		return matchedPeriods.stream()
				.filter(period -> hasText(period.label()))
				.findFirst()
				.orElse(matchedPeriods.getFirst());
	}

	private String findPeriodLabel(String text, int start, int end) {
		int contextStart = Math.max(0, start - 160);
		int contextEnd = Math.min(text.length(), end + 40);
		String context = normalizeForSearch(text.substring(contextStart, contextEnd));
		return PERIOD_LABELS.stream()
				.filter(label -> context.contains(label.normalized()))
				.map(PeriodLabel::displayName)
				.findFirst()
				.orElse(null);
	}

	private String buildPeriodReason(List<MatchedPeriod> matchedPeriods, MatchedPeriod selected) {
		String summary = matchedPeriods.stream()
				.limit(8)
				.map(period -> {
					if (period.label() == null) {
						return period.periodText();
					}
					return period.label() + " " + period.periodText();
				})
				.collect(Collectors.joining("; "));
		String selectedLabel = selected.label() == null ? "대표 기간" : selected.label();
		String source = "table row".equals(selected.source()) ? "Table row mapped Korean period pattern matched." : "Rule-based Korean period pattern matched.";
		return source + " " + selectedLabel + " 기준으로 저장했습니다. 감지한 기간: " + summary;
	}

	private Document fetch(String url) {
		try {
			return Jsoup.connect(url)
					.userAgent("SwimPulseBot/1.0 (+https://swimpulse.local)")
					.timeout(8_000)
					.followRedirects(true)
					.get();
		} catch (IOException exception) {
			throw new BadRequestException("Notice page fetch failed: " + exception.getMessage());
		}
	}

	private boolean sameHost(String baseUrl, String nextUrl) {
		try {
			String baseHost = URI.create(baseUrl).getHost();
			String nextHost = URI.create(nextUrl).getHost();
			return baseHost != null && nextHost != null && baseHost.equalsIgnoreCase(nextHost);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private boolean containsAny(String value, List<String> keywords) {
		if (!hasText(value)) {
			return false;
		}
		return keywords.stream().anyMatch(value::contains);
	}

	private boolean hasMonthKeyword(String value) {
		YearMonth currentMonth = YearMonth.now(SEOUL);
		return hasMonthKeyword(value, currentMonth) || hasMonthKeyword(value, currentMonth.plusMonths(1));
	}

	private boolean hasMonthKeyword(String value, YearMonth month) {
		int monthValue = month.getMonthValue();
		return value.contains(monthValue + "월")
				|| value.contains("0" + monthValue + "월")
				|| value.contains(monthValue + " 월")
				|| value.contains("0" + monthValue + " 월");
	}

	private String normalizeForSearch(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase().replaceAll("\\s+", "");
	}

	private String normalizeFacilityName(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase()
				.replaceAll("<[^>]*>", "")
				.replaceAll("[\\s\\u00a0]+", "")
				.replace("실내", "")
				.replace("실외", "")
				.replace("수영장", "")
				.replaceAll("[^0-9a-z가-힣]", "");
	}

	private String normalizeCellText(String value) {
		if (value == null) {
			return "";
		}
		return value.replace('\u00a0', ' ')
				.replaceAll("\\s+", " ")
				.trim();
	}

	private String firstText(String value, String fallback) {
		if (!hasText(value)) {
			return fallback;
		}
		return value.trim();
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}

	private record NoticeDetailCandidate(String url, String title) {
	}

	private record PeriodLabel(String normalized, String displayName) {
	}

	private record MatchedPeriod(LocalDate startsAt, LocalDate endsAt, String label, String periodText, String source) {
	}
}
