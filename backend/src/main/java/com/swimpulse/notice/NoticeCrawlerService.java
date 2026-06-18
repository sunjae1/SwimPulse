package com.swimpulse.notice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.NotFoundException;
import com.swimpulse.common.RedisLockService;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoticeCrawlerService {
	private static final Logger log = LoggerFactory.getLogger(NoticeCrawlerService.class);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	static final int CURRENT_PARSER_VERSION = 5;
	private static final String OCR_TEXT_MARKER = "[OCR IMAGE TEXT]";
	private static final Pattern PERIOD = Pattern.compile("(\\d{1,2})\\s*[./월]\\s*(\\d{1,2})\\s*[일.]?\\s*(?:\\([^)]*\\))?\\s*[~\\-–]\\s*(\\d{1,2})\\s*[./월]\\s*(\\d{1,2})\\s*[일.]?\\s*(?:\\([^)]*\\))?");
	private static final Pattern MONTH_TO_DAY_PERIOD = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일?\\s*[~\\-–]\\s*(\\d{1,2})\\s*일");
	private static final Pattern MONTH_TO_MONTH_END_PERIOD = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일?\\s*[~\\-–]\\s*말일");
	private static final Pattern MONTH_SINGLE_DAY = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일(?!\\s*[~\\-–])");
	private static final Pattern DAY_ONLY_PERIOD = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*\\.\\s*(?:\\([^)]*\\))?\\s*[~\\-–]\\s*(\\d{1,2})\\s*\\.\\s*(?:\\([^)]*\\))?(?!\\s*\\d)");
	private static final String MONTHLY_PREFIX_PATTERN = "(?:매월|매달)";
	private static final String OPTIONAL_KOREAN_TIME_PATTERN = "(?:\\s*\\d{1,2}\\s*(?:시\\s*(?:\\d{1,2}\\s*분?)?|[:：]\\s*\\d{2}))?";
	private static final Pattern MONTHLY_DAY_PERIOD = Pattern.compile(MONTHLY_PREFIX_PATTERN + "\\s*(\\d{1,2})\\s*일?" + OPTIONAL_KOREAN_TIME_PATTERN + "\\s*[~\\-–]\\s*(\\d{1,2})\\s*일" + OPTIONAL_KOREAN_TIME_PATTERN);
	private static final Pattern MONTHLY_TO_NEXT_MONTH_DAY_PERIOD = Pattern.compile(MONTHLY_PREFIX_PATTERN + "\\s*(\\d{1,2})\\s*일?" + OPTIONAL_KOREAN_TIME_PATTERN + "\\s*[~\\-–]\\s*익월\\s*(\\d{1,2})\\s*일" + OPTIONAL_KOREAN_TIME_PATTERN);
	private static final Pattern MONTHLY_TO_MONTH_END_PERIOD = Pattern.compile(MONTHLY_PREFIX_PATTERN + "\\s*(\\d{1,2})\\s*일?" + OPTIONAL_KOREAN_TIME_PATTERN + "\\s*[~\\-–]\\s*말일" + OPTIONAL_KOREAN_TIME_PATTERN);
	private static final Pattern MONTHLY_SINGLE_DAY = Pattern.compile(MONTHLY_PREFIX_PATTERN + "\\s*(\\d{1,2})\\s*일" + OPTIONAL_KOREAN_TIME_PATTERN + "(?!\\s*[~\\-–])");
	private static final Pattern QUARTERLY_DAY_PERIOD = Pattern.compile("분기별\\s*\\[([^]]+)]\\s*(\\d{1,2})\\s*일?\\s*[~\\-–]\\s*(\\d{1,2})\\s*일");
	private static final Pattern FN_VIEW = Pattern.compile("fn_view\\s*\\(\\s*(\\d+)\\s*\\)");
	private static final Pattern OCR_EXPLICIT_DATE = Pattern.compile("(\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일|" + MONTHLY_PREFIX_PATTERN + "\\s*\\d{1,2}\\s*일)");
	private static final Pattern OCR_DATE_TIME_RANGE = Pattern.compile(
			"((?:\\d{1,2}\\s*월\\s*)?\\d{1,2}\\s*일(?:\\([^\\n)]{0,10}\\))?)\\s+\\d{1,2}:\\d{2}\\s*[~\\-–]\\s*((?:\\d{1,2}\\s*월\\s*)?\\d{1,2}\\s*일(?:\\([^\\n)]{0,10}\\))?)\\s+\\d{1,2}:\\d{2}"
	);
	private static final List<String> OCR_DECORATIVE_IMAGE_KEYWORDS = List.of(
			"logo",
			"avatar",
			"barcode",
			"mberbarcode"
	);
	private static final List<String> OCR_SEGMENT_KEYWORDS = List.of(
			"접수기간",
			"재등록",
			"반변경",
			"신규",
			"추첨",
			"잔여",
			"회원모집",
			"온라인",
			"현장접수",
			"접수"
	);
	private static final List<String> NOTICE_LIST_KEYWORDS = List.of("공지", "회원모집", "회원모집안내", "모집안내", "수강", "수강신청안내", "접수", "프로그램", "교육", "강좌");
	private static final List<String> DETAIL_KEYWORDS = List.of("수강", "회원", "접수", "모집", "등록", "수영", "강습");
	private static final List<String> VERIFIED_SOURCE_KEYWORDS = List.of(
			"공지사항",
			"알림마당",
			"회원모집",
			"수강신청",
			"접수안내",
			"접수기간",
			"신규접수",
			"notice",
			"board"
	);
	private static final List<String> RENTAL_PAGE_KEYWORDS = List.of("대관", "대관예약", "대관신청", "대관이용");
	private static final List<String> NON_REGISTRATION_PERIOD_KEYWORDS = List.of("환불", "환불금액", "수강료", "개강", "종강", "월단위강습제", "첫수업일", "이용일수", "공제");
	private static final List<PeriodLabel> PERIOD_LABELS = List.of(
			new PeriodLabel("신규추첨접수온라인", "신규접수"),
			new PeriodLabel("신규추첨접수", "신규접수"),
			new PeriodLabel("신규잔여석접수온라인", "신규접수"),
			new PeriodLabel("신규잔여석접수", "신규접수"),
			new PeriodLabel("잔여석접수온라인", "신규접수"),
			new PeriodLabel("잔여석접수", "신규접수"),
			new PeriodLabel("잔여선착순", "신규접수"),
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
	private final NoticeImageOcrService noticeImageOcrService;
	private final ObjectMapper objectMapper;
	private final NoticeRegistrationPeriodService registrationPeriodService;
	private final NoticeOcrQueuePublisher noticeOcrQueuePublisher;
	private final MeterRegistry meterRegistry;
	private final boolean insecureSslFallbackEnabled;
	private final RedisLockService redisLockService;
	private final StringRedisTemplate redisTemplate;
	private final String scanLockKeyPrefix;
	private final Duration scanLockTtl;
	private final String scanResultKeyPrefix;
	private final Duration scanResultTtl;
	private final Duration scanWaitTimeout;
	private final Duration scanWaitPollInterval;
	private final Duration sourceDiscoveryInterval;
	private final Duration failedSourceRetryInterval;
	private final int sourceFailureThreshold;

	NoticeCrawlerService(
			PoolRepository poolRepository,
			PoolNoticeRepository noticeRepository,
			PoolNoticeSourceRepository sourceRepository,
			OpenAiNoticeExtractionClient openAiNoticeExtractionClient,
			ObjectMapper objectMapper,
			boolean insecureSslFallbackEnabled
	) {
		this(
				poolRepository,
				noticeRepository,
				sourceRepository,
				openAiNoticeExtractionClient,
				objectMapper,
				NoticeImageOcrService.NO_OP,
				insecureSslFallbackEnabled
		);
	}

	NoticeCrawlerService(
			PoolRepository poolRepository,
			PoolNoticeRepository noticeRepository,
			PoolNoticeSourceRepository sourceRepository,
			OpenAiNoticeExtractionClient openAiNoticeExtractionClient,
			ObjectMapper objectMapper,
			NoticeImageOcrService noticeImageOcrService,
			boolean insecureSslFallbackEnabled
	) {
		this(
				poolRepository,
				noticeRepository,
				sourceRepository,
				openAiNoticeExtractionClient,
				objectMapper,
				noticeImageOcrService,
				null,
				null,
				null,
				insecureSslFallbackEnabled,
				null,
				null,
				"swimpulse:locks:notice-scan:",
				300000,
				"swimpulse:notice-scan:result:",
				60000,
				15000,
				250,
				86400000,
				604800000,
				3
		);
	}

	@Autowired
	public NoticeCrawlerService(
			PoolRepository poolRepository,
			PoolNoticeRepository noticeRepository,
			PoolNoticeSourceRepository sourceRepository,
			OpenAiNoticeExtractionClient openAiNoticeExtractionClient,
			ObjectMapper objectMapper,
			NoticeImageOcrService noticeImageOcrService,
			NoticeRegistrationPeriodService registrationPeriodService,
			NoticeOcrQueuePublisher noticeOcrQueuePublisher,
			MeterRegistry meterRegistry,
			@Value("${swimpulse.notice.insecure-ssl-fallback:false}") boolean insecureSslFallbackEnabled,
			RedisLockService redisLockService,
			StringRedisTemplate redisTemplate,
			@Value("${swimpulse.notice.scan-lock-key-prefix:swimpulse:locks:notice-scan:}") String scanLockKeyPrefix,
			@Value("${swimpulse.notice.scan-lock-ttl-ms:300000}") long scanLockTtlMs,
			@Value("${swimpulse.notice.scan-result-key-prefix:swimpulse:notice-scan:result:}") String scanResultKeyPrefix,
			@Value("${swimpulse.notice.scan-result-ttl-ms:60000}") long scanResultTtlMs,
			@Value("${swimpulse.notice.scan-wait-timeout-ms:15000}") long scanWaitTimeoutMs,
			@Value("${swimpulse.notice.scan-wait-poll-ms:250}") long scanWaitPollMs,
			@Value("${swimpulse.notice.source-discovery-interval-ms:86400000}") long sourceDiscoveryIntervalMs,
			@Value("${swimpulse.notice.failed-source-retry-interval-ms:604800000}") long failedSourceRetryIntervalMs,
			@Value("${swimpulse.notice.source-failure-threshold:3}") int sourceFailureThreshold
	) {
		this.poolRepository = poolRepository;
		this.noticeRepository = noticeRepository;
		this.sourceRepository = sourceRepository;
		this.openAiNoticeExtractionClient = openAiNoticeExtractionClient;
		this.noticeImageOcrService = noticeImageOcrService == null ? NoticeImageOcrService.NO_OP : noticeImageOcrService;
		this.objectMapper = objectMapper;
		this.registrationPeriodService = registrationPeriodService;
		this.noticeOcrQueuePublisher = noticeOcrQueuePublisher;
		this.meterRegistry = meterRegistry;
		this.insecureSslFallbackEnabled = insecureSslFallbackEnabled;
		this.redisLockService = redisLockService;
		this.redisTemplate = redisTemplate;
		this.scanLockKeyPrefix = scanLockKeyPrefix;
		this.scanLockTtl = Duration.ofMillis(scanLockTtlMs);
		this.scanResultKeyPrefix = scanResultKeyPrefix;
		this.scanResultTtl = Duration.ofMillis(scanResultTtlMs);
		this.scanWaitTimeout = Duration.ofMillis(scanWaitTimeoutMs);
		this.scanWaitPollInterval = Duration.ofMillis(scanWaitPollMs);
		this.sourceDiscoveryInterval = Duration.ofMillis(sourceDiscoveryIntervalMs);
		this.failedSourceRetryInterval = Duration.ofMillis(failedSourceRetryIntervalMs);
		this.sourceFailureThreshold = sourceFailureThreshold;
	}

	@Transactional
	public NoticeScanResponse scan(Long poolId) {
		if (redisLockService == null || redisTemplate == null) {
			return runScan(poolId, null);
		}
		Optional<RedisLockService.LockToken> lockToken = tryAcquireScanLock(poolId);
		if (lockToken.isPresent()) {
			return runScan(poolId, lockToken.get());
		}
		return waitForSharedScanResult(poolId);
	}

	@Transactional
	public NoticeSourceReverificationResponse reverifySources(Integer requestedLimit) {
		int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(requestedLimit, 20));
		Instant now = Instant.now();
		List<Pool> pools = poolRepository.findPoolsNeedingNoticeSourceVerification(
				NoticeSourceStatus.CANDIDATE,
				NoticeSourceStatus.FAILED,
				NoticeSourceStatus.VERIFIED,
				now.minus(failedSourceRetryInterval),
				now.minus(sourceDiscoveryInterval),
				PageRequest.of(0, limit)
		);
		log.info("Notice source batch reverification started. requestedLimit={} selectedPools={}",
				requestedLimit, pools.size());

		List<NoticeSourceReverificationResult> results = new ArrayList<>();
		for (Pool pool : pools) {
			results.add(reverifyPoolSources(pool, now));
		}
		NoticeSourceReverificationResponse response = NoticeSourceReverificationResponse.from(results);
		log.info("Notice source batch reverification completed. processedPools={} checkedSources={} verified={} inactive={} failed={}",
				response.processedPools(),
				response.checkedSources(),
				response.verifiedSources(),
				response.inactiveSources(),
				response.failedSources());
		return response;
	}

	@Transactional
	public void enrichNoticeWithOcr(Long noticeId) {
		PoolNotice notice = noticeRepository.findById(noticeId).orElse(null);
		if (notice == null) {
			log.info("Notice OCR enrichment skipped. noticeId={} reason=not-found", noticeId);
			return;
		}
		if (notice.getExtractionStatus() == NoticeExtractionStatus.EXTRACTED) {
			notice.markOcrCompleted();
			log.info("Notice OCR enrichment skipped. noticeId={} reason=already-extracted", noticeId);
			return;
		}
		notice.markOcrProcessing();

		Pool pool = notice.getPool();
		NoticeDetailCandidate candidate = new NoticeDetailCandidate(
				notice.getUrl(),
				notice.getTitle(),
				"background OCR"
		);
		try {
			ScannedNoticeDetail detail = timeNoticePhase(
					"background_ocr_enrich",
					pool.getId(),
					notice.getUrl(),
					() -> analyzeNoticeDetail(pool, candidate, true)
			);
			notice.updateExtraction(
					detail.title(),
					detail.rawText(),
					detail.extractionStatus(),
					detail.confidence(),
					detail.registrationStartsAt(),
					detail.registrationEndsAt(),
					detail.reason(),
					detail.registrationPeriodsJson()
			);
			notice.normalizeUrl();
			notice.markAnalyzed(CURRENT_PARSER_VERSION);
			if (detail.extractionStatus() == NoticeExtractionStatus.EXTRACTED && !detail.registrationPeriods().isEmpty()) {
				notice.markOcrCompleted();
			} else {
				notice.markOcrNoPeriod();
			}
			synchronizeRegistrationPeriods(notice, detail.registrationPeriods());
			log.info("Notice OCR enrichment completed. poolId={} noticeId={} status={} confidence={} periods={} url={}",
					pool.getId(),
					notice.getId(),
					notice.getExtractionStatus(),
					notice.getConfidence(),
					detail.registrationPeriods().size(),
					notice.getUrl());
		} catch (RuntimeException exception) {
			notice.markOcrFailed("이미지 공지 분석에 실패했습니다: " + exception.getMessage());
			log.warn("Notice OCR enrichment failed. poolId={} noticeId={} url={} message={}",
					pool.getId(), notice.getId(), notice.getUrl(), exception.getMessage());
		}
	}

	private NoticeSourceReverificationResult reverifyPoolSources(Pool pool, Instant now) {
		List<String> trace = new ArrayList<>();
		List<NoticeDetailCandidate> ignoredCandidates = new ArrayList<>();
		Set<String> attemptedSourceUrls = new LinkedHashSet<>();
		List<PoolNoticeSource> sources = new ArrayList<>(
				sourceRepository.findByPoolAndStatusOrderByIdAsc(pool, NoticeSourceStatus.CANDIDATE)
		);
		sources.addAll(sourceRepository.findByPoolAndStatusAndLastScannedAtBeforeOrderByIdAsc(
				pool,
				NoticeSourceStatus.FAILED,
				now.minus(failedSourceRetryInterval)
		));

		int accessFailures = 0;
		for (PoolNoticeSource source : sources) {
			attemptedSourceUrls.add(source.getSourceUrl());
			SourceInspection inspection = inspectSource(
					pool,
					pool.getHomepageUrl(),
					source,
					ignoredCandidates,
					trace,
					"배치 재검증"
			);
			if (inspection.accessFailed()) {
				accessFailures++;
			}
		}

		boolean discoveryRan = false;
		boolean hasVerified = sourceRepository.existsByPoolAndStatus(pool, NoticeSourceStatus.VERIFIED);
		if (!hasVerified && accessFailures == 0 && isNoticeDiscoveryDue(pool)) {
			discoveryRan = true;
			discoverNoticeSources(pool, ignoredCandidates, trace, attemptedSourceUrls);
		}

		List<PoolNoticeSource> currentSources = sourceRepository.findByPoolOrderByIdAsc(pool);
		int verified = countSources(currentSources, NoticeSourceStatus.VERIFIED);
		int inactive = countSources(currentSources, NoticeSourceStatus.INACTIVE);
		int failed = countSources(currentSources, NoticeSourceStatus.FAILED);
		String message = verified > 0
				? "재사용 가능한 공지 경로를 확인했습니다."
				: accessFailures > 0
						? "접근 실패를 누적했습니다. 임계치 도달 시 FAILED로 전환됩니다."
						: "검증 가능한 공지 경로를 찾지 못했습니다.";
		return new NoticeSourceReverificationResult(
				pool.getId(),
				pool.getName(),
				attemptedSourceUrls.size(),
				verified,
				inactive,
				failed,
				discoveryRan,
				message
		);
	}

	private int countSources(List<PoolNoticeSource> sources, NoticeSourceStatus status) {
		return (int) sources.stream().filter(source -> source.getStatus() == status).count();
	}

	private NoticeScanResponse runScan(Long poolId, RedisLockService.LockToken lockToken) {
		try {
		List<String> trace = new ArrayList<>();
		Pool pool = poolRepository.findById(poolId)
				.orElseThrow(() -> new NotFoundException("Pool not found: " + poolId));
		String homepageUrl = pool.getHomepageUrl();
		if (!hasText(homepageUrl)) {
			throw new BadRequestException("Pool homepageUrl is empty.");
		}
		log.info("Notice scan started. poolId={} poolName={} homepageUrl={}", pool.getId(), pool.getName(), homepageUrl);

		List<NoticeDetailCandidate> detailCandidates = new ArrayList<>();
		Set<String> attemptedSourceUrls = new LinkedHashSet<>();
		StoredSourceScanResult storedSourceResult = collectStoredSourceCandidates(
				pool,
				homepageUrl,
				detailCandidates,
				trace,
				attemptedSourceUrls
		);
		boolean pathAvailable = storedSourceResult.pathAvailable();
		if (!pathAvailable) {
			if (isNoticeDiscoveryDue(pool)) {
				DiscoveryResult discoveryResult = discoverNoticeSources(
						pool,
						detailCandidates,
						trace,
						attemptedSourceUrls
				);
				pathAvailable = discoveryResult.pathAvailable();
			} else {
				trace.add("최근 24시간 안에 전체 공지 경로를 탐색했으므로 이번 요청에서는 홈페이지 재탐색을 생략합니다.");
			}
		}
		detailCandidates = deduplicateDetailCandidates(detailCandidates);

		List<PoolNoticeResponse> notices = new ArrayList<>();
		Map<String, PoolNotice> existingNoticesByUrl = new LinkedHashMap<>();
		for (PoolNotice existing : noticeRepository.findByPool_IdOrderByIdAsc(poolId)) {
			existingNoticesByUrl.putIfAbsent(
					NoticeSourceUrlNormalizer.normalize(existing.getUrl()),
					existing
			);
		}
		for (NoticeDetailCandidate candidate : detailCandidates) {
			PoolNotice existing = existingNoticesByUrl.get(candidate.url());
			if (existing != null) {
				if (shouldRefreshExistingNotice(existing)) {
					trace.add("기존 저장 공지의 구조화 기간을 보강합니다(" + candidate.source() + "): " + candidate.title() + " -> " + candidate.url());
					notices.add(toResponse(refreshNoticeDetail(existing, pool, candidate)));
					continue;
				}
				trace.add("기존 저장 공지 재사용(" + candidate.source() + "): " + candidate.title() + " -> " + candidate.url());
				notices.add(toResponse(existing));
				continue;
			}
			trace.add("상세 공지 본문 분석(" + candidate.source() + "): " + candidate.title() + " -> " + candidate.url());
			PoolNotice saved = scanNoticeDetail(pool, candidate);
			existingNoticesByUrl.put(candidate.url(), saved);
			notices.add(toResponse(saved));
		}
		boolean latestCheckFailed = !pathAvailable;
		String message;
		if (notices.isEmpty() && latestCheckFailed) {
			List<PoolNoticeResponse> previousNotices = noticeRepository.findTop20ByPoolIdOrderByIdDesc(poolId)
					.stream()
					.map(this::toResponse)
					.toList();
			if (previousNotices.isEmpty()) {
				message = "현재 공지 경로를 찾지 못했습니다.";
			} else {
				notices.addAll(previousNotices);
				message = "최신 공지 경로 확인에 실패해 이전에 저장된 공지 결과를 표시합니다.";
				trace.add("최신 경로 확인 실패로 기존 저장 공지 " + previousNotices.size() + "개를 반환합니다.");
			}
		} else if (notices.isEmpty()) {
			message = "현재 확인된 모집 공지가 없습니다.";
		} else {
			message = "공지 확인이 완료되었습니다.";
		}
		log.info("Notice scan completed. poolId={} detailCandidates={} savedNotices={} message={}",
				pool.getId(), detailCandidates.size(), notices.size(), message);
		NoticeScanResponse response = new NoticeScanResponse(
				pool.getId(),
				pool.getName(),
				homepageUrl,
				detailCandidates.size(),
				notices,
				message,
				trace,
				false,
				false,
				latestCheckFailed
		);
		cacheSharedScanResult(poolId, lockToken, response);
		return response;
		} finally {
			releaseScanLock(lockToken);
		}
	}

	private Optional<RedisLockService.LockToken> tryAcquireScanLock(Long poolId) {
		if (redisLockService == null) {
			return Optional.empty();
		}
		String lockKey = scanLockKeyPrefix + poolId;
		return redisLockService.acquire(lockKey, scanLockTtl);
	}

	private void releaseScanLock(RedisLockService.LockToken lockToken) {
		if (redisLockService == null) {
			return;
		}
		redisLockService.release(lockToken);
	}

	private NoticeScanResponse waitForSharedScanResult(Long poolId) {
		String lockKey = scanLockKeyPrefix + poolId;
		String activeScanToken = redisTemplate.opsForValue().get(lockKey);
		if (!hasText(activeScanToken)) {
			Optional<RedisLockService.LockToken> retry = tryAcquireScanLock(poolId);
			if (retry.isPresent()) {
				return runScan(poolId, retry.get());
			}
			throw new BadRequestException("Notice scan is already running for this pool. Try again shortly.");
		}

		Instant deadline = Instant.now().plus(scanWaitTimeout);
		while (Instant.now().isBefore(deadline)) {
			SharedNoticeScanResult cached = readSharedScanResult(poolId);
			if (cached != null && activeScanToken.equals(cached.scanToken())) {
				return sharedResponse(cached.response());
			}
			if (!hasText(redisTemplate.opsForValue().get(lockKey))) {
				break;
			}
			sleepQuietly(scanWaitPollInterval);
		}

		SharedNoticeScanResult cached = readSharedScanResult(poolId);
		if (cached != null && activeScanToken.equals(cached.scanToken())) {
			return sharedResponse(cached.response());
		}

		Optional<RedisLockService.LockToken> retry = tryAcquireScanLock(poolId);
		if (retry.isPresent()) {
			return runScan(poolId, retry.get());
		}
		throw new BadRequestException("Notice scan is taking longer than usual. Please try again shortly.");
	}

	private NoticeScanResponse sharedResponse(NoticeScanResponse response) {
		List<String> trace = new ArrayList<>();
		trace.add("다른 사용자가 먼저 시작한 동일 pool 스캔이 완료될 때까지 잠시 대기한 뒤 결과를 공유했습니다.");
		if (response.trace() != null) {
			trace.addAll(response.trace());
		}
		return new NoticeScanResponse(
				response.poolId(),
				response.poolName(),
				response.homepageUrl(),
				response.scannedLinks(),
				response.notices(),
				"Another user already started this scan. The completed result was shared with your request.",
				trace,
				true,
				true,
				response.latestCheckFailed()
		);
	}

	private void cacheSharedScanResult(Long poolId, RedisLockService.LockToken lockToken, NoticeScanResponse response) {
		if (redisTemplate == null || lockToken == null) {
			return;
		}
		try {
			String payload = objectMapper.writeValueAsString(new SharedNoticeScanResult(lockToken.token(), response));
			redisTemplate.opsForValue().set(scanResultKeyPrefix + poolId, payload, scanResultTtl);
		} catch (JsonProcessingException exception) {
			log.warn("Notice scan result cache write failed. poolId={} message={}", poolId, exception.getMessage());
		}
	}

	private SharedNoticeScanResult readSharedScanResult(Long poolId) {
		if (redisTemplate == null) {
			return null;
		}
		String payload = redisTemplate.opsForValue().get(scanResultKeyPrefix + poolId);
		if (!hasText(payload)) {
			return null;
		}
		try {
			return objectMapper.readValue(payload, SharedNoticeScanResult.class);
		} catch (JsonProcessingException exception) {
			log.warn("Notice scan result cache read failed. poolId={} message={}", poolId, exception.getMessage());
			return null;
		}
	}

	private void sleepQuietly(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BadRequestException("Notice scan wait was interrupted.");
		}
	}

	private StoredSourceScanResult collectStoredSourceCandidates(
			Pool pool,
			String homepageUrl,
			List<NoticeDetailCandidate> detailCandidates,
			List<String> trace,
			Set<String> attemptedSourceUrls
	) {
		List<PoolNoticeSource> sources = sourceRepository.findByPoolAndStatusOrderByIdAsc(
				pool,
				NoticeSourceStatus.VERIFIED
		);
		String sourceLabel = "검증된 공지 경로";
		if (sources.isEmpty()) {
			sources = sourceRepository.findByPoolAndStatusOrderByIdAsc(pool, NoticeSourceStatus.CANDIDATE);
			sourceLabel = "검증 대기 공지 경로";
		}
		if (sources.isEmpty()) {
			trace.add("저장된 VERIFIED 또는 CANDIDATE 공지 경로가 없습니다.");
			return new StoredSourceScanResult(0, 0, 0, false);
		}

		trace.add(sourceLabel + " " + sources.size() + "개를 먼저 확인합니다.");
		int successfulFetches = 0;
		int failedFetches = 0;
		boolean pathAvailable = false;
		for (PoolNoticeSource source : sources) {
			attemptedSourceUrls.add(source.getSourceUrl());
			SourceInspection inspection = inspectSource(
					pool,
					homepageUrl,
					source,
					detailCandidates,
					trace,
					sourceLabel
			);
			if (inspection.accessFailed()) {
				failedFetches++;
			} else {
				successfulFetches++;
			}
			pathAvailable = pathAvailable || inspection.verified();
		}
		return new StoredSourceScanResult(sources.size(), successfulFetches, failedFetches, pathAvailable);
	}

	private DiscoveryResult discoverNoticeSources(
			Pool pool,
			List<NoticeDetailCandidate> detailCandidates,
			List<String> trace,
			Set<String> attemptedSourceUrls
	) {
		String homepageUrl = pool.getHomepageUrl();
		pool.markNoticeDiscoveryAttempt();
		trace.add("홈페이지에서 시설명 메뉴 영역을 새로 탐색합니다: " + homepageUrl);
		List<String> noticeListUrls = discoverFacilityScopedNoticeListUrls(homepageUrl, pool.getName(), trace);
		if (noticeListUrls.isEmpty()) {
			trace.add("시설명 메뉴 영역에서 공지 목록을 못 찾아 홈페이지 전체 링크를 탐색합니다.");
			noticeListUrls = discoverNoticeListUrls(homepageUrl, trace, "홈페이지");
		}

		boolean pathAvailable = collectDiscoveredSources(
				pool,
				homepageUrl,
				noticeListUrls,
				detailCandidates,
				trace,
				"홈페이지 탐색",
				attemptedSourceUrls
		);
		if (!pathAvailable) {
			trace.add("검증 가능한 공지 경로가 없어 시설명 링크를 루트로 바꾸는 fallback을 실행합니다.");
			List<String> facilityPageUrls = discoverFacilityPageUrls(homepageUrl, pool.getName(), trace);
			for (String facilityPageUrl : facilityPageUrls) {
				trace.add("fallback 루트 탐색: " + facilityPageUrl);
				List<String> fallbackUrls = discoverFacilityScopedNoticeListUrls(
						facilityPageUrl,
						pool.getName(),
						trace
				);
				if (fallbackUrls.isEmpty()) {
					fallbackUrls = discoverNoticeListUrls(facilityPageUrl, trace, "fallback 시설 페이지");
				}
				pathAvailable = collectDiscoveredSources(
						pool,
						facilityPageUrl,
						fallbackUrls,
						detailCandidates,
						trace,
						"fallback 시설 페이지",
						attemptedSourceUrls
				);
				if (pathAvailable) {
					break;
				}
			}
		}
		return new DiscoveryResult(pathAvailable);
	}

	private boolean collectDiscoveredSources(
			Pool pool,
			String rootUrl,
			List<String> sourceUrls,
			List<NoticeDetailCandidate> detailCandidates,
			List<String> trace,
			String contextLabel,
			Set<String> attemptedSourceUrls
	) {
		if (sourceUrls.isEmpty()) {
			trace.add(contextLabel + "에서 공지 경로 후보를 찾지 못했습니다.");
			return false;
		}
		boolean pathAvailable = false;
		for (String sourceUrl : sourceUrls) {
			PoolNoticeSource source = getOrCreateSource(pool, sourceUrl);
			if (source.getStatus() == NoticeSourceStatus.FAILED && !isFailedSourceRetryDue(source)) {
				trace.add(contextLabel + " FAILED 경로 재시도 유예: " + source.getSourceUrl());
				continue;
			}
			if (!attemptedSourceUrls.add(source.getSourceUrl())) {
				trace.add(contextLabel + " 중복 경로 재요청 생략: " + source.getSourceUrl());
				continue;
			}
			SourceInspection inspection = inspectSource(
					pool,
					rootUrl,
					source,
					detailCandidates,
					trace,
					contextLabel
			);
			pathAvailable = pathAvailable || inspection.verified();
		}
		return pathAvailable;
	}

	private PoolNoticeSource getOrCreateSource(Pool pool, String sourceUrl) {
		String normalizedUrl = NoticeSourceUrlNormalizer.normalize(sourceUrl);
		return sourceRepository.findByPoolAndSourceUrl(pool, normalizedUrl)
				.orElseGet(() -> sourceRepository.save(
						new PoolNoticeSource(pool, normalizedUrl, NoticeSourceType.NOTICE_PAGE)
				));
	}

	private SourceInspection inspectSource(
			Pool pool,
			String rootUrl,
			PoolNoticeSource source,
			List<NoticeDetailCandidate> detailCandidates,
			List<String> trace,
			String contextLabel
	) {
		String sourceUrl = source.getSourceUrl();
		try {
			Document document = timeNoticePhase("source_fetch", pool.getId(), sourceUrl, () -> fetch(sourceUrl));
			List<NoticeDetailCandidate> found = discoverDetailNoticeUrls(rootUrl, sourceUrl, document);
			boolean verified = !found.isEmpty() || isLikelyNoticeSource(document, sourceUrl);
			if (verified) {
				source.markVerified();
				detailCandidates.addAll(found);
				trace.add(contextLabel + " 검증 성공: " + sourceUrl + " / 상세 후보 " + found.size() + "개");
				for (NoticeDetailCandidate candidate : found) {
					trace.add("상세 후보 출처: " + candidate.source() + " - "
							+ firstText(candidate.title(), "(제목 없음)") + " -> " + candidate.url());
				}
				log.info("Notice source verified. poolId={} sourceUrl={} detailCandidates={}",
						pool.getId(), sourceUrl, found.size());
				return new SourceInspection(true, false);
			}
			source.markInactive();
			trace.add(contextLabel + " 관련 없음 처리: " + sourceUrl);
			log.info("Notice source marked inactive. poolId={} sourceUrl={}", pool.getId(), sourceUrl);
			return new SourceInspection(false, false);
		} catch (RuntimeException exception) {
			source.markFailure(exception.getMessage(), sourceFailureThreshold);
			trace.add(contextLabel + " 접근 실패(" + source.getFailureCount() + "/" + sourceFailureThreshold + "): "
					+ sourceUrl + " / " + exception.getMessage());
			log.warn("Notice source access failed. poolId={} sourceUrl={} failureCount={} status={} message={}",
					pool.getId(), sourceUrl, source.getFailureCount(), source.getStatus(), exception.getMessage());
			return new SourceInspection(false, true);
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
			Set<String> seenFacilityUrls = new LinkedHashSet<>();
			for (Element facilityLink : facilityLinks) {
				String facilityUrl = NoticeSourceUrlNormalizer.normalize(facilityLink.absUrl("href"));
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
				String absoluteUrl = NoticeSourceUrlNormalizer.normalize(link.absUrl("href"));
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
				String absoluteUrl = NoticeSourceUrlNormalizer.normalize(link.absUrl("href"));
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

	private List<NoticeDetailCandidate> discoverDetailNoticeUrls(
			String homepageUrl,
			String noticeListUrl,
			Document document
	) {
		Set<NoticeDetailCandidate> candidates = new LinkedHashSet<>();
		Element content = noticeContentScope(document);
		for (Element link : content.select("a[href]")) {
			String title = firstText(link.text(), link.attr("title"));
			String absoluteUrl = resolveDetailNoticeUrl(noticeListUrl, document, link);
			if (!hasText(title) || !hasText(absoluteUrl) || !sameHost(homepageUrl, absoluteUrl)) {
				continue;
			}
			if (isDetailNoticeCandidate(title, link.attr("href"), link.attr("onclick"))) {
				candidates.add(new NoticeDetailCandidate(absoluteUrl, title, detailCandidateSource(link)));
			}
			if (candidates.size() >= MAX_DETAIL_URLS_PER_LIST) {
				break;
			}
		}
		if (candidates.isEmpty() && isInlineNoticePage(document)) {
			String title = firstText(document.title(), "접수 안내");
			candidates.add(new NoticeDetailCandidate(
					NoticeSourceUrlNormalizer.normalize(noticeListUrl),
					title,
					"inline page"
			));
			log.info("Notice list page itself will be analyzed as detail. url={} title={}", noticeListUrl, title);
		}
		return candidates.stream().toList();
	}

	private boolean isLikelyNoticeSource(Document document, String sourceUrl) {
		if (isInlineNoticePage(document)) {
			return true;
		}
		Element content = noticeContentScope(document);
		String headingText = content.select("h1, h2, h3, h4, caption, .title, .subject, .board-title")
				.text();
		String signal = normalizeForSearch(document.title() + " " + headingText + " " + sourceUrl);
		if (containsAny(signal, RENTAL_PAGE_KEYWORDS)) {
			return false;
		}
		boolean strongKeyword = containsAny(signal, VERIFIED_SOURCE_KEYWORDS);
		boolean boardStructure = !content.select(
				"table tbody tr, [class*=board] a[href], [id*=board] a[href], "
						+ "[class*=notice] a[href], [id*=notice] a[href]"
		).isEmpty();
		return strongKeyword && boardStructure;
	}

	private boolean isDetailNoticeCandidate(String anchorText, String href) {
		return isDetailNoticeCandidate(anchorText, href, null);
	}

	private boolean isDetailNoticeCandidate(String anchorText, String href, String onclick) {
		String haystack = normalizeForSearch(anchorText + " " + href + " " + onclick);
		if (containsAny(haystack, RENTAL_PAGE_KEYWORDS)) {
			return false;
		}
		return hasMonthKeyword(haystack) && containsAny(haystack, DETAIL_KEYWORDS);
	}

	private String resolveDetailNoticeUrl(String noticeListUrl, Document document, Element link) {
		String rawHref = link.attr("href");
		String absoluteUrl = NoticeSourceUrlNormalizer.normalize(link.absUrl("href"));
		if (hasText(absoluteUrl) && !isPlaceholderLink(rawHref)) {
			return absoluteUrl;
		}

		String onclick = link.attr("onclick");
		Matcher matcher = FN_VIEW.matcher(onclick);
		if (!matcher.find()) {
			return null;
		}

		String seq = matcher.group(1);
		if (!hasText(seq)) {
			return null;
		}

		String bbsId = extractBbsId(noticeListUrl, document);
		if (!hasText(bbsId)) {
			return null;
		}

		try {
			URI noticeListUri = URI.create(noticeListUrl);
			String detailPath = detailViewPath(noticeListUri.getPath());
			if (!hasText(detailPath)) {
				return null;
			}
			URI detailUri = new URI(
					noticeListUri.getScheme(),
					noticeListUri.getUserInfo(),
					noticeListUri.getHost(),
					noticeListUri.getPort(),
					detailPath,
					"seq=" + seq + "&bbsId=" + bbsId,
					null
			);
			return NoticeSourceUrlNormalizer.normalize(detailUri.toASCIIString());
		} catch (IllegalArgumentException | URISyntaxException exception) {
			log.debug("Failed to resolve fn_view detail URL. noticeListUrl={} href={} onclick={} message={}",
					noticeListUrl, rawHref, onclick, exception.getMessage());
			return null;
		}
	}

	private String detailCandidateSource(Element link) {
		return isPlaceholderLink(link.attr("href")) && FN_VIEW.matcher(link.attr("onclick")).find()
				? "onclick fn_view"
				: "anchor link";
	}

	private boolean isPlaceholderLink(String href) {
		if (!hasText(href)) {
			return true;
		}
		String normalized = href.trim().toLowerCase();
		return "#".equals(normalized)
				|| "#none".equals(normalized)
				|| normalized.startsWith("javascript:");
	}

	private String extractBbsId(String noticeListUrl, Document document) {
		String fromUrl = extractQueryParameter(noticeListUrl, "bbsId");
		if (hasText(fromUrl)) {
			return fromUrl;
		}
		Element input = document.selectFirst("input[name=bbsId]");
		if (input == null) {
			return null;
		}
		return firstText(input.attr("value"), null);
	}

	private String extractQueryParameter(String url, String parameterName) {
		try {
			String query = URI.create(url).getQuery();
			if (!hasText(query)) {
				return null;
			}
			for (String pair : query.split("&")) {
				String[] tokens = pair.split("=", 2);
				if (tokens.length == 2 && parameterName.equals(tokens[0]) && hasText(tokens[1])) {
					return tokens[1];
				}
			}
			return null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private String detailViewPath(String noticeListPath) {
		if (!hasText(noticeListPath)) {
			return null;
		}
		if (noticeListPath.endsWith("/list.do")) {
			return noticeListPath.substring(0, noticeListPath.length() - "/list.do".length()) + "/view.do";
		}
		return noticeListPath.replace("/list.do", "/view.do");
	}

	private boolean isInlineNoticePage(Document document) {
		String titleHaystack = normalizeForSearch(document.title());
		if (containsAny(titleHaystack, RENTAL_PAGE_KEYWORDS)) {
			return false;
		}
		String text = noticeContentScope(document).text();
		String haystack = normalizeForSearch(text);
		return containsAny(haystack, List.of("접수기간", "접수시간", "수강신청", "회원모집", "신규접수", "매월"))
				&& containsAny(haystack, DETAIL_KEYWORDS)
				&& hasPotentialPeriodText(text);
	}

	private Element noticeContentScope(Document document) {
		Document contentDocument = document.clone();
		contentDocument.select(
				"header, nav, footer, aside, "
						+ "[role=navigation], [role=banner], [role=contentinfo], "
						+ ".gnb, .gnb_wrap, .lnb, .snb, .top-menu, .topmenu, "
						+ ".global-menu, .site-menu, .sitemap, .breadcrumb, .location, "
						+ ".quick-menu, .quickmenu"
		).remove();
		for (String selector : List.of(
				"#conBody",
				"#conArea",
				"#container",
				"#contents",
				"#content",
				"main",
				"[role=main]",
				"article",
				"section",
				".contents_article",
				".contents",
				".content"
		)) {
			Element content = contentDocument.selectFirst(selector);
			if (content != null) {
				return content;
			}
		}
		Element body = contentDocument.body();
		return body == null ? contentDocument : body;
	}

	private List<String> selectNoticeOcrImageUrls(Document document) {
		Element content = noticeContentScope(document);
		List<List<String>> prioritizedBuckets = List.of(
				new ArrayList<>(),
				new ArrayList<>(),
				new ArrayList<>(),
				new ArrayList<>()
		);
		for (Element image : content.select("img[src]")) {
			if (!isEligibleOcrImage(image)) {
				continue;
			}
			String absoluteUrl = firstText(image.absUrl("src"), image.attr("src"));
			if (!hasText(absoluteUrl)) {
				continue;
			}
			prioritizedBuckets.get(ocrImagePriority(image)).add(absoluteUrl.trim());
		}
		Set<String> orderedUrls = new LinkedHashSet<>();
		for (List<String> bucket : prioritizedBuckets) {
			orderedUrls.addAll(bucket);
		}
		return List.copyOf(orderedUrls);
	}

	private int ocrImagePriority(Element image) {
		String src = normalizeForSearch(image.attr("src"));
		boolean inTbody = image.closest(".tbody") != null;
		boolean smartEditorUpload = src.contains("smarteditor/upload");
		if (inTbody && smartEditorUpload) {
			return 0;
		}
		if (inTbody) {
			return 1;
		}
		if (smartEditorUpload) {
			return 2;
		}
		return 3;
	}

	private boolean isEligibleOcrImage(Element image) {
		String src = firstText(image.attr("src"), "");
		if (!hasText(src)) {
			return false;
		}
		String normalizedSignal = normalizeForSearch(
				src
						+ " "
						+ firstText(image.attr("title"), "")
						+ " "
						+ firstText(image.attr("alt"), "")
						+ " "
						+ firstText(image.className(), "")
						+ " "
						+ firstText(image.id(), "")
		);
		if (normalizedSignal.contains(".svg")) {
			return false;
		}
		return !containsAny(normalizedSignal, OCR_DECORATIVE_IMAGE_KEYWORDS);
	}

	private boolean hasPotentialPeriodText(String text) {
		return PERIOD.matcher(text).find()
				|| MONTH_TO_DAY_PERIOD.matcher(text).find()
				|| MONTH_TO_MONTH_END_PERIOD.matcher(text).find()
				|| MONTH_SINGLE_DAY.matcher(text).find()
				|| MONTHLY_DAY_PERIOD.matcher(text).find()
				|| MONTHLY_TO_NEXT_MONTH_DAY_PERIOD.matcher(text).find()
				|| MONTHLY_TO_MONTH_END_PERIOD.matcher(text).find()
				|| MONTHLY_SINGLE_DAY.matcher(text).find()
				|| QUARTERLY_DAY_PERIOD.matcher(text).find();
	}

	private PoolNotice scanNoticeDetail(Pool pool, NoticeDetailCandidate candidate) {
		try {
			ScannedNoticeDetail detail = analyzeNoticeDetail(pool, candidate);
			PoolNotice notice = new PoolNotice(
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
			);
			notice.markAnalyzed(CURRENT_PARSER_VERSION);
			PoolNotice saved = noticeRepository.save(notice);
			synchronizeRegistrationPeriods(saved, detail.registrationPeriods());
			queueNoticeOcr(saved, detail);
			log.info("Notice detail saved. poolId={} noticeId={} status={} confidence={} url={}",
					pool.getId(), saved.getId(), saved.getExtractionStatus(), saved.getConfidence(), candidate.url());
			return saved;
		} catch (RuntimeException exception) {
			PoolNotice notice = new PoolNotice(
					pool,
					firstText(candidate.title(), pool.getName() + " 공지 확인 필요"),
					candidate.url(),
					null,
					NoticeExtractionStatus.FAILED,
					0.0,
					null,
					null,
					truncate(exception.getMessage(), 500)
			);
			notice.markAnalyzed(CURRENT_PARSER_VERSION);
			PoolNotice saved = noticeRepository.save(notice);
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
			existing.normalizeUrl();
			existing.markAnalyzed(CURRENT_PARSER_VERSION);
			synchronizeRegistrationPeriods(existing, detail.registrationPeriods());
			queueNoticeOcr(existing, detail);
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
			existing.markAnalyzed(CURRENT_PARSER_VERSION);
			log.warn("Notice detail refresh failed. poolId={} noticeId={} url={} message={}",
					pool.getId(), existing.getId(), candidate.url(), exception.getMessage());
			return existing;
		}
	}

	private ScannedNoticeDetail analyzeNoticeDetail(Pool pool, NoticeDetailCandidate candidate) {
		return analyzeNoticeDetail(pool, candidate, false);
	}

	private ScannedNoticeDetail analyzeNoticeDetail(Pool pool, NoticeDetailCandidate candidate, boolean allowOcr) {
		Document document = timeNoticePhase("detail_fetch", pool.getId(), candidate.url(), () -> fetch(candidate.url()));
		String pageTitle = firstText(document.title(), pool.getName() + " 공지");
		String title = firstText(candidate.title(), pageTitle);
		String text = buildNoticeBodyText(title, document);
		List<String> imageUrls = selectNoticeOcrImageUrls(document);
		if (!imageUrls.isEmpty()) {
			log.info("Notice OCR candidate images selected. url={} imageCount={} imageUrls={}",
					candidate.url(), imageUrls.size(), imageUrls);
		}
		NoticeTextExtractionOutcome extractionOutcome = timeNoticePhase(
				allowOcr ? "detail_parse_with_ocr" : "detail_rule_parse",
				pool.getId(),
				candidate.url(),
				() -> extractNoticeDetail(title, candidate.url(), text, document, imageUrls, allowOcr)
		);
		NoticeExtractionResult result = extractionOutcome.result();
		NoticeExtractionStatus status = result.hasPeriod() && result.confidence() >= 0.65
				? NoticeExtractionStatus.EXTRACTED
				: NoticeExtractionStatus.LINK_ONLY;
		boolean ocrPending = !allowOcr && !result.hasPeriod() && imageUrls != null && !imageUrls.isEmpty();
		log.info("Notice detail analyzed. poolId={} status={} confidence={} periods={} url={}",
				pool.getId(), status, result.confidence(), result.registrationPeriods().size(), candidate.url());
		return new ScannedNoticeDetail(
				selectNoticeTitle(result.title(), pageTitle, candidate.url()),
				truncate(extractionOutcome.rawText(), 20_000),
				status,
				result.confidence(),
				result.registrationStartsAt(),
				result.registrationEndsAt(),
				truncate(result.reason(), 500),
				serializeRegistrationPeriods(result.registrationPeriods()),
				result.registrationPeriods(),
				ocrPending
		);
	}

	private NoticeTextExtractionOutcome extractNoticeDetail(
			String title,
			String url,
			String text,
			Document document,
			List<String> imageUrls
	) {
		return extractNoticeDetail(title, url, text, document, imageUrls, true);
	}

	private NoticeTextExtractionOutcome extractNoticeDetail(
			String title,
			String url,
			String text,
			Document document,
			List<String> imageUrls,
			boolean allowOcr
	) {
		NoticeExtractionResult initialResult = extractByRule(title, url, text, document);
		if (initialResult.hasPeriod() || imageUrls == null || imageUrls.isEmpty()) {
			return new NoticeTextExtractionOutcome(text, initialResult);
		}

		if (!allowOcr) {
			log.info("Notice detail HTML extraction missed period. OCR queued for background enrichment. url={} imageCount={}",
					url, imageUrls.size());
			NoticeExtractionResult queuedResult = new NoticeExtractionResult(
					initialResult.title(),
					initialResult.registrationStartsAt(),
					initialResult.registrationEndsAt(),
					initialResult.confidence(),
					firstText(initialResult.reason(), "Rule-based period pattern not found.")
							+ " OCR queued for background enrichment.",
					initialResult.sourceUrl(),
					initialResult.registrationPeriods()
			);
			return new NoticeTextExtractionOutcome(text, queuedResult);
		}

		log.info("Notice detail HTML extraction missed period. Running OCR retry. url={} imageCount={}",
				url, imageUrls.size());
		NoticeImageOcrService.NoticeImageOcrResult ocrResult;
		try {
			ocrResult = timeNoticePhase("ocr_extract", null, url, () -> noticeImageOcrService.extractText(imageUrls));
		} catch (RuntimeException exception) {
			log.warn("Notice OCR retry failed unexpectedly. url={} message={}", url, exception.getMessage());
			return new NoticeTextExtractionOutcome(text, initialResult);
		}
		if (!ocrResult.hasText()) {
			log.info("Notice OCR retry skipped or produced no text. url={} reason={}",
					url, firstText(ocrResult.reason(), "No OCR text."));
			return new NoticeTextExtractionOutcome(text, initialResult);
		}

		String normalizedOcrText = preprocessOcrText(ocrResult.text());
		if (!hasText(normalizedOcrText)) {
			log.info("Notice OCR retry produced no usable normalized text. url={} rawTextLength={}",
					url, ocrResult.text() == null ? 0 : ocrResult.text().length());
			return new NoticeTextExtractionOutcome(text, initialResult);
		}

		String textWithOcr = appendOcrText(text, normalizedOcrText);
		NoticeExtractionResult retryResult = extractByOcrSegments(title, url, normalizedOcrText);
		log.info("Notice OCR retry completed. url={} extractedImages={} normalizedTextLength={} hasPeriod={} confidence={} periods={}",
				url,
				ocrResult.extractedImages(),
				normalizedOcrText.length(),
				retryResult.hasPeriod(),
				retryResult.confidence(),
				retryResult.registrationPeriods().size());
		return retryResult.hasPeriod()
				? new NoticeTextExtractionOutcome(textWithOcr, retryResult)
				: new NoticeTextExtractionOutcome(textWithOcr, initialResult);
	}

	private String appendOcrText(String text, String ocrText) {
		if (!hasText(ocrText)) {
			return text;
		}
		if (!hasText(text)) {
			return OCR_TEXT_MARKER + "\n" + ocrText.trim();
		}
		return text + "\n\n" + OCR_TEXT_MARKER + "\n" + ocrText.trim();
	}

	private String buildNoticeBodyText(String title, Document document) {
		String contentText = normalizeCellText(noticeContentScope(document).text());
		if (!hasText(contentText)) {
			contentText = normalizeCellText(document.text());
		}
		return hasText(contentText) ? title + "\n" + contentText : title;
	}

	private NoticeExtractionResult extractByOcrSegments(String title, String url, String normalizedOcrText) {
		List<String> segments = buildOcrParsingSegments(normalizedOcrText);
		log.info("Notice OCR parsing prepared. url={} segmentCount={} normalizedTextLength={}",
				url, segments.size(), normalizedOcrText.length());
		if (segments.isEmpty()) {
			return new NoticeExtractionResult(title, null, null, 0.45, "OCR segment candidate not found.", url);
		}

		List<MatchedPeriod> matchedPeriods = new ArrayList<>();
		int matchedSegmentCount = 0;
		for (int index = 0; index < segments.size(); index++) {
			String segment = segments.get(index);
			List<MatchedPeriod> segmentPeriods = findMatchedPeriods(segment);
			if (segmentPeriods.isEmpty()) {
				continue;
			}
			matchedSegmentCount++;
			matchedPeriods.addAll(segmentPeriods);
			log.info("Notice OCR segment matched. url={} segmentIndex={} periods={} segment={}",
					url, index, segmentPeriods.size(), truncate(segment, 220));
		}

		matchedPeriods = deduplicatePeriods(matchedPeriods);
		if (matchedPeriods.isEmpty()) {
			return new NoticeExtractionResult(title, null, null, 0.45, "OCR segment period pattern not found.", url);
		}

		MatchedPeriod selected = selectRepresentativePeriod(matchedPeriods);
		List<NoticeRegistrationPeriod> registrationPeriods = matchedPeriods.stream()
				.map(this::toRegistrationPeriod)
				.toList();
		return new NoticeExtractionResult(
				title,
				selected.startsAt().atStartOfDay(SEOUL).toInstant(),
				selected.endsAt().plusDays(1).atStartOfDay(SEOUL).minusSeconds(1).toInstant(),
				selected.label() == null ? 0.74 : 0.8,
				"OCR line/block parsing matched across " + matchedSegmentCount + " segment(s). "
						+ buildPeriodReason(matchedPeriods, selected),
				url,
				registrationPeriods
		);
	}

	private List<String> buildOcrParsingSegments(String normalizedOcrText) {
		if (!hasText(normalizedOcrText)) {
			return List.of();
		}
		List<String> lines = Arrays.stream(normalizedOcrText.split("\\R"))
				.map(this::normalizeCellText)
				.filter(this::hasText)
				.toList();
		Set<String> segments = new LinkedHashSet<>();
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);
			if (!isOcrSegmentCandidate(line)) {
				continue;
			}
			segments.add(line);
			if (index > 0 && isOcrContextLabelLine(lines.get(index - 1))) {
				segments.add(lines.get(index - 1) + " " + line);
			}
			if (index + 1 < lines.size() && isOcrSegmentContinuationLine(lines.get(index + 1))) {
				segments.add(line + " " + lines.get(index + 1));
			}
			if (index > 0
					&& index + 1 < lines.size()
					&& isOcrContextLabelLine(lines.get(index - 1))
					&& isOcrSegmentContinuationLine(lines.get(index + 1))) {
				segments.add(lines.get(index - 1) + " " + line + " " + lines.get(index + 1));
			}
		}
		return List.copyOf(segments);
	}

	private boolean isOcrSegmentCandidate(String line) {
		String haystack = normalizeForSearch(line);
		boolean hasDateSignal = OCR_EXPLICIT_DATE.matcher(line).find() || hasPotentialPeriodText(line);
		boolean hasKeyword = containsAny(haystack, OCR_SEGMENT_KEYWORDS) || containsAny(haystack, DETAIL_KEYWORDS);
		return hasDateSignal && hasKeyword && !isExcludedPeriodContext(line);
	}

	private boolean isOcrContextLabelLine(String line) {
		String haystack = normalizeForSearch(line);
		return !OCR_EXPLICIT_DATE.matcher(line).find()
				&& containsAny(haystack, OCR_SEGMENT_KEYWORDS)
				&& !isExcludedPeriodContext(line);
	}

	private boolean isOcrSegmentContinuationLine(String line) {
		String haystack = normalizeForSearch(line);
		return OCR_EXPLICIT_DATE.matcher(line).find()
				&& (containsAny(haystack, OCR_SEGMENT_KEYWORDS) || containsAny(haystack, DETAIL_KEYWORDS));
	}

	private String preprocessOcrText(String ocrText) {
		if (!hasText(ocrText)) {
			return "";
		}
		String normalized = ocrText
				.replace("\r\n", "\n")
				.replace('\r', '\n')
				.replace('\u00a0', ' ')
				.replace('\u200b', ' ')
				.replace('_', ' ');
		normalized = OCR_DATE_TIME_RANGE.matcher(normalized).replaceAll("$1 ~ $2");
		List<String> normalizedLines = Arrays.stream(normalized.split("\\n", -1))
				.map(this::preprocessOcrLine)
				.toList();
		return String.join("\n", normalizedLines).trim();
	}

	private String preprocessOcrLine(String line) {
		if (!hasText(line)) {
			return "";
		}
		String normalized = line.replaceAll("\\s+", " ").trim();
		normalized = normalized.replaceAll("\\(을(?!\\))", "(일)");
		normalized = normalized.replaceAll("\\((월|화|수|목|금|토|일)(?!\\))", "($1)");
		return normalized;
	}

	private String selectNoticeTitle(String extractedTitle, String fallbackTitle, String url) {
		String normalizedExtracted = normalizeWhitespace(extractedTitle);
		if (hasText(normalizedExtracted) && normalizedExtracted.length() <= 255) {
			return normalizedExtracted;
		}
		if (hasText(normalizedExtracted)) {
			log.warn("Extracted notice title exceeded column length. Falling back to page title. length={} url={}",
					normalizedExtracted.length(), url);
		}
		return truncate(normalizeWhitespace(fallbackTitle), 255);
	}

	private String normalizeWhitespace(String value) {
		return value == null ? null : value.replaceAll("\\s+", " ").trim();
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
		Matcher monthToDayMatcher = MONTH_TO_DAY_PERIOD.matcher(text);
		while (monthToDayMatcher.find()) {
			try {
				int month = Integer.parseInt(monthToDayMatcher.group(1));
				LocalDate startsAt = LocalDate.of(
						year,
						month,
						Integer.parseInt(monthToDayMatcher.group(2))
				);
				LocalDate endsAt = LocalDate.of(
						year,
						month,
						Integer.parseInt(monthToDayMatcher.group(3))
				);
				if (endsAt.isBefore(startsAt)) {
					endsAt = endsAt.plusMonths(1);
				}
				periods.add(new MatchedPeriod(
						startsAt,
						endsAt,
						resolvePeriodLabel(text, monthToDayMatcher.start(), monthToDayMatcher.end(), label),
						matchedText(text, monthToDayMatcher),
						source + " omitted end month"
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed explicit-month date fragments.
			}
		}
		Matcher monthEndMatcher = MONTH_TO_MONTH_END_PERIOD.matcher(text);
		while (monthEndMatcher.find()) {
			try {
				int month = Integer.parseInt(monthEndMatcher.group(1));
				YearMonth yearMonth = YearMonth.of(year, month);
				LocalDate startsAt = yearMonth.atDay(Integer.parseInt(monthEndMatcher.group(2)));
				periods.add(new MatchedPeriod(
						startsAt,
						yearMonth.atEndOfMonth(),
						resolvePeriodLabel(text, monthEndMatcher.start(), monthEndMatcher.end(), label),
						matchedText(text, monthEndMatcher),
						source + " explicit month end"
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed explicit-month-to-month-end fragments.
			}
		}
		Matcher singleDayMatcher = MONTH_SINGLE_DAY.matcher(text);
		while (singleDayMatcher.find()) {
			if (!isRegistrationSingleDay(text, label, singleDayMatcher)) {
				continue;
			}
			try {
				LocalDate date = LocalDate.of(
						year,
						Integer.parseInt(singleDayMatcher.group(1)),
						Integer.parseInt(singleDayMatcher.group(2))
				);
				periods.add(new MatchedPeriod(
						date,
						date,
						resolvePeriodLabel(text, singleDayMatcher.start(), singleDayMatcher.end(), label),
						matchedText(text, singleDayMatcher),
						source + " explicit single day"
				));
			} catch (DateTimeException | NumberFormatException ignored) {
				// Ignore malformed explicit single-day fragments.
			}
		}
		return periods;
	}

	private boolean isRegistrationSingleDay(String text, String label, Matcher matcher) {
		int afterEnd = Math.min(text.length(), matcher.end() + 30);
		String immediateAfter = normalizeForSearch(text.substring(matcher.end(), afterEnd));
		if (immediateAfter.matches("^(?:\\d{1,2}시)?추첨.*")) {
			return false;
		}
		int contextStart = Math.max(0, matcher.start() - 100);
		int contextEnd = Math.min(text.length(), matcher.end() + 80);
		String context = normalizeForSearch(
				firstText(label, "") + " " + text.substring(contextStart, contextEnd)
		);
		if (containsAny(context, List.of("결제", "당첨", "발표", "취소"))
				&& !containsAny(context, List.of("재등록", "반변경", "신규접수", "신규신청", "신규회원", "회원모집"))) {
			return false;
		}
		return containsAny(
				context,
				List.of("접수", "반변경", "재등록", "신규등록", "신규신청", "회원모집", "수강신청")
		);
	}

	private String matchedText(String text, Matcher matcher) {
		return text.substring(matcher.start(), matcher.end()).replaceAll("\\s+", " ").trim();
	}

	private List<MatchedPeriod> findMonthlyDayPeriodsInValue(String text, String label, String source) {
		if (!containsAny(normalizeForSearch(text), List.of("모집", "접수", "기간", "회원", "수강", "매월", "매달"))) {
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
		if (periods.isEmpty()) {
			return List.of();
		}
		Set<String> seen = new LinkedHashSet<>();
		List<MatchedPeriod> unique = new ArrayList<>();
		for (MatchedPeriod period : periods) {
			MatchedPeriod canonical = canonicalizePeriodLabel(period);
			String key = canonical.startsAt() + "|" + canonical.endsAt() + "|" + normalizeForSearch(canonical.label());
			if (seen.add(key)) {
				unique.add(canonical);
			}
		}
		return normalizeMatchedPeriods(unique);
	}

	private List<MatchedPeriod> normalizeMatchedPeriods(List<MatchedPeriod> periods) {
		List<MatchedPeriod> normalized = suppressMonthlyFalsePositives(periods);
		normalized = collapseSameRangeDuplicates(normalized);
		normalized = removeSingleDayNoiseInsideRanges(normalized);
		normalized = collapseSameRangeDuplicates(normalized);
		normalized = removeUnlabeledNoise(normalized);
		return normalized;
	}

	private MatchedPeriod canonicalizePeriodLabel(MatchedPeriod period) {
		String canonicalLabel = canonicalizePeriodLabel(period.label());
		if ((canonicalLabel == null && period.label() == null)
				|| (canonicalLabel != null && canonicalLabel.equals(period.label()))) {
			return period;
		}
		return new MatchedPeriod(
				period.startsAt(),
				period.endsAt(),
				canonicalLabel,
				period.periodText(),
				period.source()
		);
	}

	private String canonicalizePeriodLabel(String label) {
		if (!hasText(label)) {
			return null;
		}
		String normalized = normalizeForSearch(label);
		if (normalized.contains("신규추첨")
				|| normalized.contains("신규잔여")
				|| normalized.contains("잔여석접수")
				|| normalized.contains("잔여선착순")) {
			return "신규접수";
		}
		return label;
	}

	private List<MatchedPeriod> suppressMonthlyFalsePositives(List<MatchedPeriod> periods) {
		return periods.stream()
				.filter(period -> !shouldSuppressMonthlyPeriod(period, periods))
				.toList();
	}

	private boolean shouldSuppressMonthlyPeriod(MatchedPeriod candidate, List<MatchedPeriod> periods) {
		if (!candidate.source().contains("monthly")) {
			return false;
		}
		for (MatchedPeriod other : periods) {
			if (other == candidate || other.source().contains("monthly")) {
				continue;
			}
			if (sameDateRange(candidate, other)) {
				return true;
			}
			if (candidate.startsAt().equals(candidate.endsAt())
					&& isMultiDayRange(other)
					&& containsDate(other, candidate.startsAt())
					&& labelsAreCompatible(candidate.label(), other.label())) {
				return true;
			}
		}
		return false;
	}

	private List<MatchedPeriod> collapseSameRangeDuplicates(List<MatchedPeriod> periods) {
		Map<String, List<MatchedPeriod>> byRange = new LinkedHashMap<>();
		for (MatchedPeriod period : periods) {
			byRange.computeIfAbsent(rangeKey(period), ignored -> new ArrayList<>()).add(period);
		}
		List<MatchedPeriod> collapsed = new ArrayList<>();
		for (List<MatchedPeriod> sameRangePeriods : byRange.values()) {
			boolean hasMeaningfulLabel = sameRangePeriods.stream().anyMatch(period -> hasMeaningfulLabel(period.label()));
			boolean hasAnyLabel = sameRangePeriods.stream().anyMatch(period -> hasText(period.label()));
			Map<String, MatchedPeriod> bestByLabel = new LinkedHashMap<>();
			for (MatchedPeriod period : sameRangePeriods) {
				if (hasMeaningfulLabel && !hasMeaningfulLabel(period.label())) {
					continue;
				}
				if (!hasMeaningfulLabel && hasAnyLabel && !hasText(period.label())) {
					continue;
				}
				String labelKey = normalizeForSearch(period.label());
				MatchedPeriod current = bestByLabel.get(labelKey);
				if (current == null || periodPreferenceScore(period) > periodPreferenceScore(current)) {
					bestByLabel.put(labelKey, period);
				}
			}
			if (bestByLabel.isEmpty()) {
				collapsed.add(sameRangePeriods.getFirst());
				continue;
			}
			collapsed.addAll(bestByLabel.values());
		}
		return collapsed;
	}

	private List<MatchedPeriod> removeSingleDayNoiseInsideRanges(List<MatchedPeriod> periods) {
		List<MatchedPeriod> filtered = new ArrayList<>();
		for (MatchedPeriod period : periods) {
			if (!isSingleDay(period)) {
				filtered.add(period);
				continue;
			}
			boolean coveredByRange = periods.stream()
					.filter(other -> other != period)
					.anyMatch(other -> isMultiDayRange(other)
							&& containsDate(other, period.startsAt())
							&& labelsAreCompatible(period.label(), other.label()));
			if (!coveredByRange) {
				filtered.add(period);
			}
		}
		return filtered;
	}

	private List<MatchedPeriod> removeUnlabeledNoise(List<MatchedPeriod> periods) {
		boolean hasMeaningfulLabel = periods.stream().anyMatch(period -> hasMeaningfulLabel(period.label()));
		if (!hasMeaningfulLabel) {
			return periods;
		}
		return periods.stream()
				.filter(period -> hasText(period.label()))
				.toList();
	}

	private boolean hasMeaningfulLabel(String label) {
		return hasText(label) && !isGenericLabel(label);
	}

	private String labelCategory(String label) {
		if (!hasText(label)) {
			return "";
		}
		String normalized = normalizeForSearch(label);
		if (normalized.contains("재등록")) {
			return "재등록";
		}
		if (normalized.contains("반변경")) {
			return "반변경";
		}
		if (normalized.contains("신규")) {
			return "신규";
		}
		if (normalized.contains("접수기간")) {
			return "접수기간";
		}
		return normalized;
	}

	private int periodPreferenceScore(MatchedPeriod period) {
		int score = 0;
		if (hasMeaningfulLabel(period.label())) {
			score += 100;
		} else if (hasText(period.label())) {
			score += 50;
		}
		if (!period.source().contains("monthly")) {
			score += 20;
		}
		if (isMultiDayRange(period)) {
			score += 10;
		}
		score += normalizeForSearch(period.label()).length();
		return score;
	}

	private boolean sameDateRange(MatchedPeriod left, MatchedPeriod right) {
		return left.startsAt().equals(right.startsAt()) && left.endsAt().equals(right.endsAt());
	}

	private String rangeKey(MatchedPeriod period) {
		return period.startsAt() + "|" + period.endsAt();
	}

	private boolean isSingleDay(MatchedPeriod period) {
		return period.startsAt().equals(period.endsAt());
	}

	private boolean isMultiDayRange(MatchedPeriod period) {
		return !isSingleDay(period);
	}

	private boolean containsDate(MatchedPeriod range, LocalDate date) {
		return (!date.isBefore(range.startsAt())) && (!date.isAfter(range.endsAt()));
	}

	private boolean labelsAreCompatible(String firstLabel, String secondLabel) {
		if (!hasText(firstLabel) || !hasText(secondLabel)) {
			return true;
		}
		String firstCategory = labelCategory(firstLabel);
		String secondCategory = labelCategory(secondLabel);
		return firstCategory.equals(secondCategory)
				|| isGenericLabel(firstLabel)
				|| isGenericLabel(secondLabel);
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
		return notice.getParserVersion() < CURRENT_PARSER_VERSION
				|| notice.getExtractionStatus() == NoticeExtractionStatus.FAILED
				|| notice.getTitle().length() >= 255;
	}

	private PoolNoticeResponse toResponse(PoolNotice notice) {
		List<NoticeRegistrationPeriod> periods = registrationPeriodService == null
				? parseRegistrationPeriods(notice)
				: registrationPeriodService.findForResponse(notice);
		return PoolNoticeResponse.from(notice, periods);
	}

	private void synchronizeRegistrationPeriods(
			PoolNotice notice,
			List<NoticeRegistrationPeriod> registrationPeriods
	) {
		if (registrationPeriodService != null) {
			registrationPeriodService.synchronize(notice, registrationPeriods);
		}
	}

	private void queueNoticeOcr(PoolNotice notice, ScannedNoticeDetail detail) {
		if (!detail.ocrPending() || noticeOcrQueuePublisher == null) {
			notice.markOcrNotRequired();
			return;
		}
		notice.markOcrPending();
		noticeOcrQueuePublisher.publishAfterCommit(notice.getId());
		if (meterRegistry != null) {
			meterRegistry.counter(
					"swimpulse.notice.ocr.queued",
					"pool_id",
					notice.getPool() == null || notice.getPool().getId() == null
							? "unknown"
							: notice.getPool().getId().toString()
			).increment();
		}
		log.info("Notice OCR enrichment queued. poolId={} noticeId={} url={}",
				notice.getPool() == null ? null : notice.getPool().getId(), notice.getId(), notice.getUrl());
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

	private <T> T timeNoticePhase(String phase, Long poolId, String url, Supplier<T> supplier) {
		long startedAt = System.nanoTime();
		String outcome = "success";
		try {
			return supplier.get();
		} catch (RuntimeException exception) {
			outcome = "failure";
			throw exception;
		} finally {
			long elapsedNanos = System.nanoTime() - startedAt;
			log.info("Notice phase completed. phase={} outcome={} poolId={} elapsedMs={} url={}",
					phase, outcome, poolId, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), url);
			if (meterRegistry != null) {
				Timer.builder("swimpulse.notice.phase.duration")
						.description("Notice crawler phase duration")
						.tag("phase", phase)
						.tag("outcome", outcome)
						.tag("pool_id", poolId == null ? "unknown" : poolId.toString())
						.register(meterRegistry)
						.record(elapsedNanos, TimeUnit.NANOSECONDS);
			}
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

	private boolean isNoticeDiscoveryDue(Pool pool) {
		Instant lastDiscoveryAt = pool.getLastNoticeDiscoveryAt();
		return lastDiscoveryAt == null || lastDiscoveryAt.isBefore(Instant.now().minus(sourceDiscoveryInterval));
	}

	private boolean isFailedSourceRetryDue(PoolNoticeSource source) {
		Instant lastScannedAt = source.getLastScannedAt();
		return lastScannedAt == null || lastScannedAt.isBefore(Instant.now().minus(failedSourceRetryInterval));
	}

	private List<NoticeDetailCandidate> deduplicateDetailCandidates(List<NoticeDetailCandidate> candidates) {
		Set<String> seenUrls = new LinkedHashSet<>();
		List<NoticeDetailCandidate> unique = new ArrayList<>();
		for (NoticeDetailCandidate candidate : candidates) {
			String normalizedUrl = NoticeSourceUrlNormalizer.normalize(candidate.url());
			if (hasText(normalizedUrl) && seenUrls.add(normalizedUrl)) {
				unique.add(new NoticeDetailCandidate(normalizedUrl, candidate.title(), candidate.source()));
			}
		}
		return unique;
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

	private record SourceInspection(boolean verified, boolean accessFailed) {
	}

	private record StoredSourceScanResult(
			int attemptedSources,
			int successfulFetches,
			int failedFetches,
			boolean pathAvailable
	) {
	}

	private record DiscoveryResult(boolean pathAvailable) {
	}

	private record SharedNoticeScanResult(String scanToken, NoticeScanResponse response) {
	}

	private record PeriodLabel(String normalized, String displayName) {
	}

	private record MatchedPeriod(LocalDate startsAt, LocalDate endsAt, String label, String periodText, String source) {
	}

	private record NoticeTextExtractionOutcome(String rawText, NoticeExtractionResult result) {
	}

	private record ScannedNoticeDetail(
			String title,
			String rawText,
			NoticeExtractionStatus extractionStatus,
			Double confidence,
			Instant registrationStartsAt,
			Instant registrationEndsAt,
			String reason,
			String registrationPeriodsJson,
			List<NoticeRegistrationPeriod> registrationPeriods,
			boolean ocrPending
	) {
	}
}
