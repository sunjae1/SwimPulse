package com.swimpulse.notice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.NotFoundException;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoticeCrawlerService {
	private static final Logger log = LoggerFactory.getLogger(NoticeCrawlerService.class);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final Pattern PERIOD = Pattern.compile("(\\d{1,2})\\s*[./월]\\s*(\\d{1,2})\\s*[일.]?\\s*(?:\\([^)]*\\))?\\s*[~\\-–]\\s*(\\d{1,2})\\s*[./월]\\s*(\\d{1,2})\\s*[일.]?\\s*(?:\\([^)]*\\))?");
	private static final Pattern DAY_ONLY_PERIOD = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*\\.\\s*(?:\\([^)]*\\))?\\s*[~\\-–]\\s*(\\d{1,2})\\s*\\.\\s*(?:\\([^)]*\\))?(?!\\s*\\d)");
	private static final Pattern MONTHLY_DAY_PERIOD = Pattern.compile("매월\\s*(\\d{1,2})\\s*일?\\s*[~\\-–]\\s*(\\d{1,2})\\s*일");
	private static final Pattern MONTHLY_TO_NEXT_MONTH_DAY_PERIOD = Pattern.compile("매월\\s*(\\d{1,2})\\s*일?\\s*[~\\-–]\\s*익월\\s*(\\d{1,2})\\s*일");
	private static final Pattern MONTHLY_TO_MONTH_END_PERIOD = Pattern.compile("매월\\s*(\\d{1,2})\\s*일?\\s*[~\\-–]\\s*말일");
	private static final Pattern MONTHLY_SINGLE_DAY = Pattern.compile("매월\\s*(\\d{1,2})\\s*일(?!\\s*[~\\-–])");
	private static final Pattern QUARTERLY_DAY_PERIOD = Pattern.compile("분기별\\s*\\[([^]]+)]\\s*(\\d{1,2})\\s*일?\\s*[~\\-–]\\s*(\\d{1,2})\\s*일");
	private static final List<String> NOTICE_LIST_KEYWORDS = List.of("공지", "회원모집", "회원모집안내", "모집안내", "수강", "수강신청안내", "접수", "프로그램", "교육", "강좌");
	private static final List<String> DETAIL_KEYWORDS = List.of("수강", "회원", "접수", "모집", "등록", "수영", "강습");
	private static final List<String> RENTAL_PAGE_KEYWORDS = List.of("대관", "대관예약", "대관신청", "대관이용");
	private static final List<String> NON_REGISTRATION_PERIOD_KEYWORDS = List.of("환불", "환불금액", "수강료", "개강", "종강", "월단위강습제", "첫수업일", "이용일수", "공제");
	private static final List<PeriodLabel> PERIOD_LABELS = List.of(
			new PeriodLabel("신규회원모집", "신규회원모집"),
			new PeriodLabel("신규회원", "신규 회원"),
			new PeriodLabel("기존회원및추첨종목모집기간", "기존 회원 및 추첨종목 모집기간"),
			new PeriodLabel("기존회원재등록", "기존회원 재등록"),
			new PeriodLabel("성동구민우선등록회원", "성동구민 우선등록회원"),
			new PeriodLabel("일반등록회원", "일반등록회원"),
			new PeriodLabel("재등록회원", "재등록회원"),
			new PeriodLabel("수영초급반추첨모집", "수영초급반 추첨모집"),
			new PeriodLabel("아쿠아잔여모집기간", "아쿠아 잔여 모집기간"),
			new PeriodLabel("아쿠아프로그램잔여자리모집", "아쿠아프로그램 잔여자리 모집"),
			new PeriodLabel("수영신규접수", "수영 신규접수"),
			new PeriodLabel("아쿠아로빅신규접수", "아쿠아로빅 신규접수"),
			new PeriodLabel("프로그램반변경", "프로그램 반변경"),
			new PeriodLabel("신규신청", "신규신청"),
			new PeriodLabel("재등록", "재등록"),
			new PeriodLabel("반변경", "반변경"),
			new PeriodLabel("특화반", "특화반"),
			new PeriodLabel("접수기간", "접수기간"),
			new PeriodLabel("신규접수", "신규접수")
	);
	private static final int MAX_NOTICE_LIST_URLS = 6;
	private static final int MAX_DETAIL_URLS_PER_LIST = 10;

	private final PoolRepository poolRepository;
	private final PoolNoticeRepository noticeRepository;
	private final PoolNoticeSourceRepository sourceRepository;
	private final OpenAiNoticeExtractionClient openAiNoticeExtractionClient;
	private final ObjectMapper objectMapper;
	private final boolean insecureSslFallbackEnabled;

	public NoticeCrawlerService(
			PoolRepository poolRepository,
			PoolNoticeRepository noticeRepository,
			PoolNoticeSourceRepository sourceRepository,
			OpenAiNoticeExtractionClient openAiNoticeExtractionClient,
			ObjectMapper objectMapper,
			@Value("${swimpulse.notice.insecure-ssl-fallback:false}") boolean insecureSslFallbackEnabled
	) {
		this.poolRepository = poolRepository;
		this.noticeRepository = noticeRepository;
		this.sourceRepository = sourceRepository;
		this.openAiNoticeExtractionClient = openAiNoticeExtractionClient;
		this.objectMapper = objectMapper;
		this.insecureSslFallbackEnabled = insecureSslFallbackEnabled;
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
		log.info("Notice scan started. poolId={} poolName={} homepageUrl={}", pool.getId(), pool.getName(), homepageUrl);

		trace.add("홈페이지에서 시설명 메뉴 영역을 먼저 탐색합니다: " + homepageUrl);
		List<String> noticeListUrls = discoverFacilityScopedNoticeListUrls(homepageUrl, pool.getName(), trace);
		boolean directHomepageCandidates = false;
		if (noticeListUrls.isEmpty()) {
			trace.add("시설명 메뉴 영역에서 공지 목록을 못 찾아 홈페이지 전체 링크를 탐색합니다.");
			noticeListUrls = discoverNoticeListUrls(homepageUrl, trace, "홈페이지");
			directHomepageCandidates = true;
		}
		List<NoticeDetailCandidate> detailCandidates = new ArrayList<>();
		if (directHomepageCandidates) {
			collectDirectDetailCandidates(pool, noticeListUrls, detailCandidates, trace, "홈페이지 전체 링크");
		} else {
			collectDetailCandidates(pool, homepageUrl, noticeListUrls, detailCandidates, trace);
		}

		if (detailCandidates.isEmpty()) {
			trace.add("상세 공지 후보가 없어 시설명 링크를 루트로 바꾸는 fallback을 실행합니다.");
			List<String> facilityPageUrls = discoverFacilityPageUrls(homepageUrl, pool.getName(), trace);
			for (String facilityPageUrl : facilityPageUrls) {
				trace.add("fallback 루트 탐색: " + facilityPageUrl);
				List<String> fallbackNoticeListUrls = discoverFacilityScopedNoticeListUrls(facilityPageUrl, pool.getName(), trace);
				boolean directFallbackCandidates = false;
				if (fallbackNoticeListUrls.isEmpty()) {
					fallbackNoticeListUrls = discoverNoticeListUrls(facilityPageUrl, trace, "fallback 시설 페이지");
					directFallbackCandidates = true;
				}
				if (directFallbackCandidates) {
					collectDirectDetailCandidates(pool, fallbackNoticeListUrls, detailCandidates, trace, "fallback 시설 페이지 전체 링크");
				} else {
					collectDetailCandidates(pool, facilityPageUrl, fallbackNoticeListUrls, detailCandidates, trace);
				}
				if (!detailCandidates.isEmpty()) {
					break;
				}
			}
		}

		List<PoolNoticeResponse> notices = new ArrayList<>();
		for (NoticeDetailCandidate candidate : detailCandidates) {
			noticeRepository.findByUrl(candidate.url()).ifPresentOrElse(
					existing -> {
						if (shouldRefreshExistingNotice(existing)) {
							trace.add("기존 저장 공지의 구조화 기간을 보강합니다(" + candidate.source() + "): " + candidate.title() + " -> " + candidate.url());
							notices.add(toResponse(refreshNoticeDetail(existing, pool, candidate)));
							return;
						}
						trace.add("기존 저장 공지 재사용(" + candidate.source() + "): " + candidate.title() + " -> " + candidate.url());
						notices.add(toResponse(existing));
					},
					() -> {
						trace.add("상세 공지 본문 분석(" + candidate.source() + "): " + candidate.title() + " -> " + candidate.url());
						notices.add(toResponse(scanNoticeDetail(pool, candidate)));
					}
			);
		}
		String message = notices.isEmpty() ? "No detail notice candidates found." : "Notice scan completed.";
		log.info("Notice scan completed. poolId={} detailCandidates={} savedNotices={} message={}",
				pool.getId(), detailCandidates.size(), notices.size(), message);
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
				for (NoticeDetailCandidate candidate : found) {
					trace.add("상세 후보 출처: " + candidate.source() + " - "
							+ firstText(candidate.title(), "(제목 없음)") + " -> " + candidate.url());
				}
				log.info("Notice list analyzed. poolId={} sourceUrl={} detailCandidates={}",
						pool.getId(), noticeListUrl, found.size());
				source.markScanned(NoticeSourceStatus.ACTIVE);
			} catch (RuntimeException exception) {
				source.markScanned(NoticeSourceStatus.FAILED);
				trace.add("공지 목록 페이지 분석 실패: " + noticeListUrl + " / " + exception.getMessage());
				log.warn("Notice list analysis failed. poolId={} sourceUrl={} message={}",
						pool.getId(), noticeListUrl, exception.getMessage());
			}
		}
	}

	private void collectDirectDetailCandidates(
			Pool pool,
			List<String> candidateUrls,
			List<NoticeDetailCandidate> detailCandidates,
			List<String> trace,
			String contextLabel
	) {
		if (candidateUrls.isEmpty()) {
			trace.add(contextLabel + " 직접 분석 후보 URL이 없습니다.");
			return;
		}
		int before = detailCandidates.size();
		for (String candidateUrl : candidateUrls) {
			PoolNoticeSource source = sourceRepository.findByPoolAndSourceUrl(pool, candidateUrl)
					.orElseGet(() -> sourceRepository.save(new PoolNoticeSource(pool, candidateUrl, NoticeSourceType.NOTICE_PAGE)));
			try {
				Optional<NoticeDetailCandidate> candidate = discoverInlineDetailCandidate(candidateUrl);
				if (candidate.isPresent()) {
					detailCandidates.add(candidate.get());
					trace.add(contextLabel + " 직접 상세 후보 추가(" + candidate.get().source() + "): "
							+ firstText(candidate.get().title(), "(제목 없음)") + " -> " + candidateUrl);
				} else {
					trace.add(contextLabel + " 직접 분석 제외: 기간 패턴 없음 -> " + candidateUrl);
				}
				source.markScanned(NoticeSourceStatus.ACTIVE);
			} catch (RuntimeException exception) {
				source.markScanned(NoticeSourceStatus.FAILED);
				trace.add(contextLabel + " 직접 분석 후보 확인 실패: " + candidateUrl + " / " + exception.getMessage());
				log.warn("Direct notice candidate analysis failed. poolId={} url={} message={}",
						pool.getId(), candidateUrl, exception.getMessage());
			}
		}
		trace.add(contextLabel + " 직접 상세 후보 " + (detailCandidates.size() - before) + "개 발견");
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
			Set<String> seenFacilityUrls = new LinkedHashSet<>();
			for (Element facilityLink : facilityLinks) {
				String facilityUrl = facilityLink.absUrl("href");
				if (hasText(facilityUrl) && !seenFacilityUrls.add(facilityUrl)) {
					log.debug("Duplicate facility menu link skipped. poolName={} url={}", poolName, facilityUrl);
					continue;
				}
				trace.add("시설명 메뉴 발견: " + firstText(facilityLink.text(), "(텍스트 없음)") + " -> " + facilityUrl);
				Element scope = facilityLink.closest("li");
				if (scope == null) {
					continue;
				}
				addNoticeListCandidatesFrom(homepageUrl, scope, urls, maxMenuDepth(facilityLink, 1));
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
		addNoticeListCandidatesFrom(rootUrl, scope, urls, null);
	}

	private void addNoticeListCandidatesFrom(String rootUrl, Element scope, Set<String> urls, Integer maxDepth) {
		for (Element link : scope.select("a[href]")) {
			int depth = linkDepth(link);
			if (maxDepth != null && depth != Integer.MAX_VALUE && depth > maxDepth) {
				continue;
			}
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
		if (containsAny(haystack, RENTAL_PAGE_KEYWORDS)) {
			return false;
		}
		return containsAny(haystack, NOTICE_LIST_KEYWORDS);
	}

	private int maxMenuDepth(Element link, int extraDepth) {
		int depth = linkDepth(link);
		return depth == Integer.MAX_VALUE ? depth : depth + extraDepth;
	}

	private int linkDepth(Element link) {
		String depth = link.attr("data-depth");
		if (!hasText(depth)) {
			return Integer.MAX_VALUE;
		}
		try {
			return Integer.parseInt(depth);
		} catch (NumberFormatException exception) {
			return Integer.MAX_VALUE;
		}
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
				candidates.add(new NoticeDetailCandidate(absoluteUrl, title, "anchor link"));
			}
			if (candidates.size() >= MAX_DETAIL_URLS_PER_LIST) {
				break;
			}
		}
		if (candidates.isEmpty() && isInlineNoticePage(document)) {
			String title = firstText(document.title(), "접수 안내");
			candidates.add(new NoticeDetailCandidate(noticeListUrl, title, "inline page"));
			log.info("Notice list page itself will be analyzed as detail. url={} title={}", noticeListUrl, title);
		}
		return candidates.stream().toList();
	}

	private Optional<NoticeDetailCandidate> discoverInlineDetailCandidate(String url) {
		Document document = fetch(url);
		if (!isInlineNoticePage(document)) {
			return Optional.empty();
		}
		return Optional.of(new NoticeDetailCandidate(url, firstText(document.title(), "접수 안내"), "inline page"));
	}

	private boolean isDetailNoticeCandidate(String anchorText, String href) {
		String haystack = normalizeForSearch(anchorText + " " + href);
		if (containsAny(haystack, RENTAL_PAGE_KEYWORDS)) {
			return false;
		}
		return hasMonthKeyword(haystack) && containsAny(haystack, DETAIL_KEYWORDS);
	}

	private boolean isInlineNoticePage(Document document) {
		String titleHaystack = normalizeForSearch(document.title());
		if (containsAny(titleHaystack, RENTAL_PAGE_KEYWORDS)) {
			return false;
		}
		String text = document.text();
		String haystack = normalizeForSearch(text);
		return containsAny(haystack, List.of("접수기간", "접수시간", "수강신청", "회원모집", "신규접수", "매월"))
				&& containsAny(haystack, DETAIL_KEYWORDS)
				&& hasPotentialPeriodText(text);
	}

	private boolean hasPotentialPeriodText(String text) {
		return PERIOD.matcher(text).find()
				|| MONTHLY_DAY_PERIOD.matcher(text).find()
				|| MONTHLY_TO_NEXT_MONTH_DAY_PERIOD.matcher(text).find()
				|| MONTHLY_TO_MONTH_END_PERIOD.matcher(text).find()
				|| MONTHLY_SINGLE_DAY.matcher(text).find()
				|| QUARTERLY_DAY_PERIOD.matcher(text).find();
	}

	private PoolNotice scanNoticeDetail(Pool pool, NoticeDetailCandidate candidate) {
		try {
			ScannedNoticeDetail detail = analyzeNoticeDetail(pool, candidate);
			PoolNotice saved = noticeRepository.save(new PoolNotice(
					pool,
					detail.title(),
					candidate.url(),
					detail.rawText(),
					detail.extractionStatus(),
					detail.confidence(),
					detail.registrationStartsAt(),
					detail.registrationEndsAt(),
					detail.reason(),
					detail.registrationPeriodsJson()
			));
			log.info("Notice detail saved. poolId={} noticeId={} status={} confidence={} url={}",
					pool.getId(), saved.getId(), saved.getExtractionStatus(), saved.getConfidence(), candidate.url());
			return saved;
		} catch (RuntimeException exception) {
			PoolNotice saved = noticeRepository.save(new PoolNotice(
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
			log.warn("Notice detail scan failed. poolId={} noticeId={} url={} message={}",
					pool.getId(), saved.getId(), candidate.url(), exception.getMessage());
			return saved;
		}
	}

	private PoolNotice refreshNoticeDetail(PoolNotice existing, Pool pool, NoticeDetailCandidate candidate) {
		try {
			ScannedNoticeDetail detail = analyzeNoticeDetail(pool, candidate);
			existing.updateExtraction(
					detail.title(),
					detail.rawText(),
					detail.extractionStatus(),
					detail.confidence(),
					detail.registrationStartsAt(),
					detail.registrationEndsAt(),
					detail.reason(),
					detail.registrationPeriodsJson()
			);
			log.info("Notice detail refreshed. poolId={} noticeId={} status={} confidence={} url={}",
					pool.getId(), existing.getId(), existing.getExtractionStatus(), existing.getConfidence(), candidate.url());
			return existing;
		} catch (RuntimeException exception) {
			existing.updateExtraction(
					firstText(candidate.title(), pool.getName() + " 공지 확인 필요"),
					null,
					NoticeExtractionStatus.FAILED,
					0.0,
					null,
					null,
					truncate(exception.getMessage(), 500),
					null
			);
			log.warn("Notice detail refresh failed. poolId={} noticeId={} url={} message={}",
					pool.getId(), existing.getId(), candidate.url(), exception.getMessage());
			return existing;
		}
	}

	private ScannedNoticeDetail analyzeNoticeDetail(Pool pool, NoticeDetailCandidate candidate) {
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
		log.info("Notice detail analyzed. poolId={} status={} confidence={} periods={} url={}",
				pool.getId(), status, result.confidence(), result.registrationPeriods().size(), candidate.url());
		return new ScannedNoticeDetail(
				firstText(result.title(), title),
				truncate(text, 20_000),
				status,
				result.confidence(),
				result.registrationStartsAt(),
				result.registrationEndsAt(),
				truncate(result.reason(), 500),
				serializeRegistrationPeriods(result.registrationPeriods())
		);
	}

	private NoticeExtractionResult extractByRule(String title, String url, String text, Document document) {
		String haystack = normalizeForSearch(title + " " + text);
		if (!containsAny(haystack, DETAIL_KEYWORDS)) {
			return new NoticeExtractionResult(title, null, null, 0.2, "Registration keywords not found.", url);
		}
		List<MatchedPeriod> matchedPeriods = new ArrayList<>();
		matchedPeriods.addAll(findTableMatchedPeriods(document));
		matchedPeriods.addAll(findBlockMatchedPeriods(document));
		if (matchedPeriods.isEmpty()) {
			matchedPeriods.addAll(findMatchedPeriods(text));
		}
		matchedPeriods = deduplicatePeriods(matchedPeriods);
		if (matchedPeriods.isEmpty()) {
			return new NoticeExtractionResult(title, null, null, 0.45, "Period pattern not found.", url);
		}
		MatchedPeriod selected = selectRepresentativePeriod(matchedPeriods);
		List<NoticeRegistrationPeriod> registrationPeriods = matchedPeriods.stream()
				.map(this::toRegistrationPeriod)
				.toList();
		return new NoticeExtractionResult(
				title,
				selected.startsAt().atStartOfDay(SEOUL).toInstant(),
				selected.endsAt().plusDays(1).atStartOfDay(SEOUL).minusSeconds(1).toInstant(),
				selected.label() == null ? 0.75 : 0.82,
				buildPeriodReason(matchedPeriods, selected),
				url,
				registrationPeriods
		);
	}

	private List<MatchedPeriod> findTableMatchedPeriods(Document document) {
		List<MatchedPeriod> periods = new ArrayList<>();
		for (Element table : document.select("table")) {
			if (isExcludedPeriodContext(table.text())) {
				continue;
			}
			List<String> columnHeaders = extractColumnHeaders(table);
			for (Element row : table.select("tr")) {
				if (row.parents().stream().anyMatch(parent -> "thead".equalsIgnoreCase(parent.tagName()))
						|| isExcludedPeriodContext(row.text())) {
					continue;
				}
				List<Element> cells = row.select("> th, > td");
				if (cells.size() < 2) {
					continue;
				}
				for (int index = 0; index < cells.size(); index++) {
					String cellText = normalizeCellText(cells.get(index).text());
					if (!hasText(cellText) || isExcludedPeriodContext(cellText)) {
						continue;
					}
					String label = resolveTableCellLabel(row, cells, index, columnHeaders);
					periods.addAll(findMatchedPeriodsInValue(
							cellText,
							label,
							"table cell"
					));
					periods.addAll(findDayOnlyPeriodsInValue(
							cellText,
							label,
							inferMonthFromRow(row.text()),
							"table cell"
					));
					periods.addAll(findMonthlyDayPeriodsInValue(
							cellText,
							label,
							"table cell"
					));
				}
			}
		}
		return deduplicatePeriods(periods);
	}

	private List<String> extractColumnHeaders(Element table) {
		List<String> headers = table.select("thead tr").stream()
				.reduce((first, second) -> second)
				.map(row -> row.select("> th, > td").stream()
						.map(cell -> normalizeCellText(cell.text()))
						.toList())
				.orElseGet(List::of);
		if (!headers.isEmpty()) {
			return headers;
		}
		Element firstHeaderRow = table.selectFirst("tr:has(th)");
		if (firstHeaderRow == null) {
			return List.of();
		}
		return firstHeaderRow.select("> th, > td").stream()
				.map(cell -> normalizeCellText(cell.text()))
				.toList();
	}

	private List<MatchedPeriod> findBlockMatchedPeriods(Document document) {
		List<MatchedPeriod> periods = new ArrayList<>();
		String sectionLabel = null;
		for (Element element : document.select("h1, h2, h3, h4, h5, h6, .tt_txt, p, li, dd, dt")) {
			if (element.parents().stream().anyMatch(parent -> "table".equalsIgnoreCase(parent.tagName()))) {
				continue;
			}
			String text = normalizeCellText(element.text());
			if (!hasText(text) || isExcludedPeriodContext(text)) {
				continue;
			}
			if (isSectionLabelElement(element) || !hasPotentialPeriodText(text)) {
				String label = findSectionLabel(text);
				if (hasText(label)) {
					sectionLabel = label;
				}
			}
			if (!hasPotentialPeriodText(text)) {
				continue;
			}
			periods.addAll(findMatchedPeriodsInValue(text, sectionLabel, "content block"));
			periods.addAll(findMonthlyDayPeriodsInValue(text, sectionLabel, "content block"));
		}
		return deduplicatePeriods(periods);
	}

	private boolean isSectionLabelElement(Element element) {
		String tagName = element.tagName();
		return tagName.matches("h[1-6]") || element.hasClass("tt_txt");
	}

	private String findSectionLabel(String text) {
		String label = findPeriodLabel(text, 0, text.length());
		if (hasText(label)) {
			return label;
		}
		String normalized = normalizeForSearch(text);
		if (normalized.contains("신규신청")) {
			return "신규신청";
		}
		if (normalized.contains("반변경")) {
			return "반변경";
		}
		if (normalized.contains("재등록")) {
			return "재등록";
		}
		return null;
	}

	private boolean isExcludedPeriodContext(String text) {
		return containsAny(normalizeForSearch(text), NON_REGISTRATION_PERIOD_KEYWORDS);
	}

	private List<MatchedPeriod> findMatchedPeriods(String text) {
		if (isExcludedPeriodContext(text)) {
			return List.of();
		}
		List<MatchedPeriod> periods = new ArrayList<>(findMatchedPeriodsInValue(text, null, "text"));
		periods.addAll(findMonthlyDayPeriodsInValue(text, null, "text"));
		return deduplicatePeriods(periods);
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
						resolvePeriodLabel(text, matcher.start(), matcher.end(), label),
						text.substring(matcher.start(), matcher.end()).replaceAll("\\s+", " ").trim(),
						source
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed date fragments that merely look like registration periods.
			}
		}
		return periods;
	}

	private List<MatchedPeriod> findMonthlyDayPeriodsInValue(String text, String label, String source) {
		if (!containsAny(normalizeForSearch(text), List.of("모집", "접수", "기간", "회원", "수강", "매월"))) {
			return List.of();
		}
		List<MatchedPeriod> periods = new ArrayList<>();
		Matcher quarterlyMatcher = QUARTERLY_DAY_PERIOD.matcher(text);
		while (quarterlyMatcher.find()) {
			try {
				LocalDate[] range = resolveQuarterlyDayRange(
						quarterlyMatcher.group(1),
						Integer.parseInt(quarterlyMatcher.group(2)),
						Integer.parseInt(quarterlyMatcher.group(3))
				);
				periods.add(new MatchedPeriod(
						range[0],
						range[1],
						resolvePeriodLabel(text, quarterlyMatcher.start(), quarterlyMatcher.end(), label),
						text.substring(quarterlyMatcher.start(), quarterlyMatcher.end()).replaceAll("\\s+", " ").trim(),
						source + " quarterly"
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed quarterly date fragments.
			}
		}
		Matcher monthEndMatcher = MONTHLY_TO_MONTH_END_PERIOD.matcher(text);
		while (monthEndMatcher.find()) {
			try {
				LocalDate[] range = resolveMonthlyDayRange(
						Integer.parseInt(monthEndMatcher.group(1)),
						null,
						true
				);
				periods.add(new MatchedPeriod(
						range[0],
						range[1],
						resolvePeriodLabel(text, monthEndMatcher.start(), monthEndMatcher.end(), label),
						text.substring(monthEndMatcher.start(), monthEndMatcher.end()).replaceAll("\\s+", " ").trim(),
						source + " monthly"
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed monthly date fragments.
			}
		}
		Matcher nextMonthMatcher = MONTHLY_TO_NEXT_MONTH_DAY_PERIOD.matcher(text);
		while (nextMonthMatcher.find()) {
			try {
				LocalDate[] range = resolveMonthlyNextMonthDayRange(
						Integer.parseInt(nextMonthMatcher.group(1)),
						Integer.parseInt(nextMonthMatcher.group(2))
				);
				periods.add(new MatchedPeriod(
						range[0],
						range[1],
						resolvePeriodLabel(text, nextMonthMatcher.start(), nextMonthMatcher.end(), label),
						text.substring(nextMonthMatcher.start(), nextMonthMatcher.end()).replaceAll("\\s+", " ").trim(),
						source + " monthly"
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed next-month date fragments.
			}
		}
		Matcher matcher = MONTHLY_DAY_PERIOD.matcher(text);
		while (matcher.find()) {
			try {
				LocalDate[] range = resolveMonthlyDayRange(
						Integer.parseInt(matcher.group(1)),
						Integer.parseInt(matcher.group(2)),
						false
				);
				periods.add(new MatchedPeriod(
						range[0],
						range[1],
						resolvePeriodLabel(text, matcher.start(), matcher.end(), label),
						text.substring(matcher.start(), matcher.end()).replaceAll("\\s+", " ").trim(),
						source + " monthly"
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed monthly date fragments.
			}
		}
		Matcher singleDayMatcher = MONTHLY_SINGLE_DAY.matcher(text);
		while (singleDayMatcher.find()) {
			try {
				LocalDate[] range = resolveMonthlyDayRange(
						Integer.parseInt(singleDayMatcher.group(1)),
						Integer.parseInt(singleDayMatcher.group(1)),
						false
				);
				periods.add(new MatchedPeriod(
						range[0],
						range[1],
						resolvePeriodLabel(text, singleDayMatcher.start(), singleDayMatcher.end(), label),
						text.substring(singleDayMatcher.start(), singleDayMatcher.end()).replaceAll("\\s+", " ").trim(),
						source + " monthly"
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed monthly date fragments.
			}
		}
		return periods;
	}

	private LocalDate[] resolveMonthlyDayRange(int startDay, Integer endDay, boolean endsAtMonthEnd) {
		LocalDate today = LocalDate.now(SEOUL);
		YearMonth month = YearMonth.from(today);
		LocalDate startsAt = month.atDay(startDay);
		LocalDate endsAt = resolveMonthlyEndDate(month, startDay, endDay, endsAtMonthEnd);
		if (endsAt.isBefore(today)) {
			YearMonth nextMonth = month.plusMonths(1);
			startsAt = nextMonth.atDay(startDay);
			endsAt = resolveMonthlyEndDate(nextMonth, startDay, endDay, endsAtMonthEnd);
		}
		return new LocalDate[] {startsAt, endsAt};
	}

	private LocalDate resolveMonthlyEndDate(YearMonth startMonth, int startDay, Integer endDay, boolean endsAtMonthEnd) {
		if (endsAtMonthEnd) {
			return startMonth.atEndOfMonth();
		}
		if (endDay == null) {
			return startMonth.atDay(startDay);
		}
		return endDay >= startDay ? startMonth.atDay(endDay) : startMonth.plusMonths(1).atDay(endDay);
	}

	private LocalDate[] resolveMonthlyNextMonthDayRange(int startDay, int nextMonthEndDay) {
		LocalDate today = LocalDate.now(SEOUL);
		YearMonth month = YearMonth.from(today);
		LocalDate startsAt = month.atDay(startDay);
		LocalDate endsAt = month.plusMonths(1).atDay(nextMonthEndDay);
		if (endsAt.isBefore(today)) {
			YearMonth nextMonth = month.plusMonths(1);
			startsAt = nextMonth.atDay(startDay);
			endsAt = nextMonth.plusMonths(1).atDay(nextMonthEndDay);
		}
		return new LocalDate[] {startsAt, endsAt};
	}

	private LocalDate[] resolveQuarterlyDayRange(String monthsText, int startDay, int endDay) {
		List<Integer> months = Pattern.compile("\\d{1,2}")
				.matcher(monthsText)
				.results()
				.map(result -> Integer.parseInt(result.group()))
				.filter(month -> month >= 1 && month <= 12)
				.distinct()
				.sorted()
				.toList();
		if (months.isEmpty()) {
			throw new DateTimeException("Quarterly month list is empty.");
		}
		LocalDate today = LocalDate.now(SEOUL);
		for (int yearOffset = 0; yearOffset <= 1; yearOffset++) {
			int year = today.getYear() + yearOffset;
			for (Integer month : months) {
				YearMonth yearMonth = YearMonth.of(year, month);
				LocalDate startsAt = yearMonth.atDay(startDay);
				LocalDate endsAt = endDay >= startDay ? yearMonth.atDay(endDay) : yearMonth.plusMonths(1).atDay(endDay);
				if (!endsAt.isBefore(today)) {
					return new LocalDate[] {startsAt, endsAt};
				}
			}
		}
		throw new DateTimeException("No upcoming quarterly range found.");
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
						resolvePeriodLabel(text, matcher.start(), matcher.end(), label),
						text.substring(matcher.start(), matcher.end()).replaceAll("\\s+", " ").trim(),
						source
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed date fragments that merely look like registration periods.
			}
		}
		return periods;
	}

	private String resolveTableCellLabel(Element row, List<Element> cells, int periodCellIndex, List<String> columnHeaders) {
		String rowText = row.text();
		String knownLabel = findPeriodLabel(rowText, 0, rowText.length());
		if (hasText(knownLabel) && !isGenericLabel(knownLabel)) {
			return knownLabel;
		}
		for (int index = 0; index < cells.size(); index++) {
			if (index == periodCellIndex) {
				continue;
			}
			String cellText = normalizeCellText(cells.get(index).text());
			if (!hasText(cellText) || hasPotentialPeriodText(cellText) || isExcludedPeriodContext(cellText)) {
				continue;
			}
			String cellLabel = findPeriodLabel(cellText, 0, cellText.length());
			if (hasText(cellLabel) && !isGenericLabel(cellLabel)) {
				return cellLabel;
			}
		}
		if (periodCellIndex < columnHeaders.size()) {
			String columnHeader = normalizeCellText(columnHeaders.get(periodCellIndex));
			if (hasText(columnHeader) && !isExcludedPeriodContext(columnHeader)) {
				String label = findPeriodLabel(columnHeader, 0, columnHeader.length());
				return hasText(label) ? label : truncate(columnHeader, 80);
			}
		}
		if (hasText(knownLabel)) {
			return knownLabel;
		}
		for (int index = 0; index < periodCellIndex; index++) {
			String cellText = normalizeCellText(cells.get(index).text());
			if (hasText(cellText) && !hasPotentialPeriodText(cellText) && !isExcludedPeriodContext(cellText)) {
				return truncate(cellText, 80);
			}
		}
		return null;
	}

	private boolean isGenericLabel(String label) {
		return "접수기간".equals(normalizeForSearch(label));
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

	private NoticeRegistrationPeriod toRegistrationPeriod(MatchedPeriod period) {
		return new NoticeRegistrationPeriod(
				period.label(),
				period.startsAt().atStartOfDay(SEOUL).toInstant(),
				period.endsAt().plusDays(1).atStartOfDay(SEOUL).minusSeconds(1).toInstant(),
				period.periodText(),
				period.source()
		);
	}

	private String resolvePeriodLabel(String text, int start, int end, String fallbackLabel) {
		String precedingLabel = findSpecificPeriodLabelBefore(text, start);
		if (hasText(precedingLabel)) {
			return precedingLabel;
		}
		if (hasText(fallbackLabel)) {
			return fallbackLabel;
		}
		return findPeriodLabel(text, start, end);
	}

	private String findPeriodLabel(String text, int start, int end) {
		int contextStart = Math.max(0, start - 160);
		int contextEnd = Math.min(text.length(), end + 40);
		String beforeLabel = findPeriodLabelBefore(text.substring(contextStart, Math.max(contextStart, start)));
		if (hasText(beforeLabel)) {
			return beforeLabel;
		}
		String afterContext = normalizeForSearch(text.substring(Math.max(0, start), contextEnd));
		return findFirstPeriodLabel(afterContext);
	}

	private String findPeriodLabelBefore(String text, int end) {
		return findPeriodLabelBefore(text.substring(Math.max(0, end - 120), Math.max(0, end)));
	}

	private String findSpecificPeriodLabelBefore(String text, int end) {
		return findSpecificPeriodLabelBefore(text.substring(Math.max(0, end - 120), Math.max(0, end)));
	}

	private String findSpecificPeriodLabelBefore(String text) {
		PeriodLabel closest = findClosestPeriodLabel(normalizeForSearch(text), false);
		return closest == null ? null : closest.displayName();
	}

	private String findPeriodLabelBefore(String text) {
		String context = normalizeForSearch(text);
		PeriodLabel closest = findClosestPeriodLabel(context, false);
		if (closest == null) {
			closest = findClosestPeriodLabel(context, true);
		}
		return closest == null ? null : closest.displayName();
	}

	private PeriodLabel findClosestPeriodLabel(String context, boolean includeGenericLabels) {
		PeriodLabel closest = null;
		int closestEndIndex = -1;
		for (PeriodLabel label : PERIOD_LABELS) {
			if (!includeGenericLabels && isGenericPeriodLabel(label)) {
				continue;
			}
			int index = context.lastIndexOf(label.normalized());
			int endIndex = index + label.normalized().length();
			if (index >= 0 && (endIndex > closestEndIndex
					|| (endIndex == closestEndIndex && label.normalized().length() > closest.normalized().length()))) {
				closest = label;
				closestEndIndex = endIndex;
			}
		}
		return closest;
	}

	private boolean isGenericPeriodLabel(PeriodLabel label) {
		return "접수기간".equals(label.normalized());
	}

	private String findFirstPeriodLabel(String normalizedContext) {
		PeriodLabel first = null;
		int firstIndex = Integer.MAX_VALUE;
		for (PeriodLabel label : PERIOD_LABELS) {
			int index = normalizedContext.indexOf(label.normalized());
			if (index >= 0 && index < firstIndex) {
				first = label;
				firstIndex = index;
			}
		}
		return first == null ? null : first.displayName();
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
		String source = selected.source() != null && selected.source().startsWith("table")
				? "Table cell mapped Korean period pattern matched."
				: "Rule-based Korean period pattern matched.";
		return source + " " + selectedLabel + " 기준으로 저장했습니다. 감지한 기간: " + summary;
	}

	private boolean shouldRefreshExistingNotice(PoolNotice notice) {
		if (!hasText(notice.getRegistrationPeriodsJson())) {
			return true;
		}
		return parseRegistrationPeriods(notice).size() < 2;
	}

	private PoolNoticeResponse toResponse(PoolNotice notice) {
		return PoolNoticeResponse.from(notice, parseRegistrationPeriods(notice));
	}

	private String serializeRegistrationPeriods(List<NoticeRegistrationPeriod> registrationPeriods) {
		try {
			return objectMapper.writeValueAsString(registrationPeriods == null ? List.of() : registrationPeriods);
		} catch (JsonProcessingException exception) {
			throw new BadRequestException("Registration periods serialization failed: " + exception.getMessage());
		}
	}

	private List<NoticeRegistrationPeriod> parseRegistrationPeriods(PoolNotice notice) {
		if (hasText(notice.getRegistrationPeriodsJson())) {
			try {
				List<NoticeRegistrationPeriod> periods = objectMapper.readValue(notice.getRegistrationPeriodsJson(), new TypeReference<>() {
				});
				if (!periods.isEmpty()) {
					return periods;
				}
			} catch (JsonProcessingException ignored) {
				// Fall through to the legacy single-period fields.
			}
		}
		if (notice.getRegistrationStartsAt() != null && notice.getRegistrationEndsAt() != null) {
			return List.of(new NoticeRegistrationPeriod(
					null,
					notice.getRegistrationStartsAt(),
					notice.getRegistrationEndsAt(),
					null,
					"legacy"
			));
		}
		return List.of();
	}

	private Document fetch(String url) {
		try {
			log.debug("Fetching notice page. url={}", url);
			return connect(url, true);
		} catch (IOException exception) {
			if (insecureSslFallbackEnabled && isCertificateValidationFailure(exception)) {
				try {
					log.warn("Notice page TLS certificate validation failed. Retrying without certificate validation. url={} message={}",
							url, exception.getMessage());
					return connect(url, false);
				} catch (IOException fallbackException) {
					throw new BadRequestException("Notice page fetch failed after insecure SSL fallback: " + fallbackException.getMessage());
				}
			}
			throw new BadRequestException("Notice page fetch failed: " + exception.getMessage());
		}
	}

	private Document connect(String url, boolean validateTlsCertificates) throws IOException {
		Connection connection = Jsoup.connect(url)
				.userAgent("SwimPulseBot/1.0 (+https://swimpulse.local)")
				.timeout(8_000)
				.followRedirects(true);
		if (!validateTlsCertificates) {
			connection.sslSocketFactory(insecureSslSocketFactory());
		}
		return connection.get();
	}

	private boolean isCertificateValidationFailure(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof SSLHandshakeException) {
				return true;
			}
			String message = current.getMessage();
			if (message != null && (message.contains("PKIX path building failed")
					|| message.contains("unable to find valid certification path")
					|| message.contains("certificate_unknown"))) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private SSLSocketFactory insecureSslSocketFactory() {
		try {
			TrustManager[] trustAllCerts = new TrustManager[] {
					new X509TrustManager() {
						@Override
						public void checkClientTrusted(X509Certificate[] chain, String authType) {
						}

						@Override
						public void checkServerTrusted(X509Certificate[] chain, String authType) {
						}

						@Override
						public X509Certificate[] getAcceptedIssuers() {
							return new X509Certificate[0];
						}
					}
			};
			SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, trustAllCerts, new SecureRandom());
			return sslContext.getSocketFactory();
		} catch (GeneralSecurityException exception) {
			throw new BadRequestException("Insecure SSL fallback could not be initialized: " + exception.getMessage());
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

	private record NoticeDetailCandidate(String url, String title, String source) {
	}

	private record PeriodLabel(String normalized, String displayName) {
	}

	private record MatchedPeriod(LocalDate startsAt, LocalDate endsAt, String label, String periodText, String source) {
	}

	private record ScannedNoticeDetail(
			String title,
			String rawText,
			NoticeExtractionStatus extractionStatus,
			Double confidence,
			Instant registrationStartsAt,
			Instant registrationEndsAt,
			String reason,
			String registrationPeriodsJson
	) {
	}
}
