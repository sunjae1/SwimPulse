package com.swimpulse.notice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.event.RegistrationEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoticeRegistrationPeriodService {
	private static final Logger log = LoggerFactory.getLogger(NoticeRegistrationPeriodService.class);

	private final NoticeRegistrationPeriodRepository periodRepository;
	private final PoolNoticeRepository noticeRepository;
	private final RegistrationEventRepository eventRepository;
	private final ObjectMapper objectMapper;

	public NoticeRegistrationPeriodService(
			NoticeRegistrationPeriodRepository periodRepository,
			PoolNoticeRepository noticeRepository,
			RegistrationEventRepository eventRepository,
			ObjectMapper objectMapper
	) {
		this.periodRepository = periodRepository;
		this.noticeRepository = noticeRepository;
		this.eventRepository = eventRepository;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public List<NoticeRegistrationPeriod> synchronize(
			PoolNotice notice,
			List<NoticeRegistrationPeriod> parsedPeriods
	) {
		Map<PeriodKey, NoticeRegistrationPeriod> incoming = normalizeIncoming(parsedPeriods);
		List<NoticeRegistrationPeriodEntity> existing = periodRepository.findByNotice_IdOrderByStartsAtAscIdAsc(
				notice.getId()
		);
		Map<PeriodKey, NoticeRegistrationPeriodEntity> existingByKey = new LinkedHashMap<>();
		for (NoticeRegistrationPeriodEntity period : existing) {
			existingByKey.put(PeriodKey.from(period), period);
		}

		List<NoticeRegistrationPeriodEntity> active = new ArrayList<>();
		for (Map.Entry<PeriodKey, NoticeRegistrationPeriod> entry : incoming.entrySet()) {
			NoticeRegistrationPeriodEntity entity = existingByKey.remove(entry.getKey());
			if (entity == null) {
				entity = new NoticeRegistrationPeriodEntity(notice, entry.getValue());
			} else {
				entity.updateFrom(entry.getValue());
			}
			active.add(entity);
		}
		existingByKey.values().forEach(NoticeRegistrationPeriodEntity::markInactive);
		periodRepository.saveAll(existingByKey.values());
		active = periodRepository.saveAll(active);
		notice.markPeriodsMigrated();

		log.info("Notice registration periods synchronized. noticeId={} activePeriods={} inactivePeriods={}",
				notice.getId(), active.size(), existingByKey.size());
		return active.stream()
				.sorted(Comparator.comparing(NoticeRegistrationPeriodEntity::getStartsAt)
						.thenComparing(
								NoticeRegistrationPeriodEntity::getId,
								Comparator.nullsLast(Long::compareTo)
						))
				.map(NoticeRegistrationPeriodEntity::toDto)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<NoticeRegistrationPeriod> findForResponse(PoolNotice notice) {
		List<NoticeRegistrationPeriodEntity> periods =
				periodRepository.findByNotice_IdAndStatusOrderByStartsAtAscIdAsc(
						notice.getId(),
						NoticeRegistrationPeriodStatus.ACTIVE
				);
		if (!periods.isEmpty()) {
			return periods.stream().map(NoticeRegistrationPeriodEntity::toDto).toList();
		}
		if (notice.getPeriodsMigratedAt() != null && notice.getPeriodsMigrationError() == null) {
			return List.of();
		}
		return parseLegacyPeriods(notice).periods();
	}

	@Transactional
	public NoticePeriodMigrationResponse migrateLegacyPeriods(Integer requestedLimit) {
		int limit = requestedLimit == null ? 50 : Math.max(1, Math.min(requestedLimit, 100));
		List<PoolNotice> notices = noticeRepository.findPendingPeriodMigration(PageRequest.of(0, limit));
		List<NoticePeriodMigrationResult> results = new ArrayList<>();

		for (PoolNotice notice : notices) {
			try {
				LegacyParseResult parsed = parseLegacyPeriods(notice);
				if (parsed.error() != null && parsed.periods().isEmpty()) {
					notice.markPeriodsMigrationFailed(parsed.error());
					results.add(new NoticePeriodMigrationResult(
							notice.getId(),
							notice.getPool().getId(),
							notice.getTitle(),
							0,
							0,
							false,
							false,
							parsed.error()
					));
					continue;
				}

				List<NoticeRegistrationPeriod> synchronizedPeriods = synchronize(notice, parsed.periods());
				int linkedEvents = linkExistingEvents(notice, synchronizedPeriods);
				results.add(new NoticePeriodMigrationResult(
						notice.getId(),
						notice.getPool().getId(),
						notice.getTitle(),
						synchronizedPeriods.size(),
						linkedEvents,
						parsed.legacyFallbackUsed(),
						true,
						"이관 완료"
				));
			} catch (RuntimeException exception) {
				notice.markPeriodsMigrationFailed(exception.getMessage());
				results.add(new NoticePeriodMigrationResult(
						notice.getId(),
						notice.getPool().getId(),
						notice.getTitle(),
						0,
						0,
						false,
						false,
						exception.getMessage()
				));
				log.warn("Notice period migration failed. noticeId={} message={}",
						notice.getId(), exception.getMessage());
			}
		}

		NoticePeriodMigrationResponse response = NoticePeriodMigrationResponse.from(results);
		log.info("Notice period migration completed. processed={} migrated={} activePeriods={} linkedEvents={} failed={}",
				response.processedNotices(),
				response.migratedNotices(),
				response.activePeriods(),
				response.linkedEvents(),
				response.failedNotices());
		return response;
	}

	@Transactional(readOnly = true)
	public NoticePeriodMigrationStatus migrationStatus() {
		long totalNotices = noticeRepository.count();
		long pendingNotices = noticeRepository.countByPeriodsMigratedAtIsNull();
		long failedNotices = noticeRepository.countByPeriodsMigrationErrorIsNotNull();
		return new NoticePeriodMigrationStatus(
				totalNotices,
				totalNotices - pendingNotices - failedNotices,
				pendingNotices,
				failedNotices,
				periodRepository.countByStatus(NoticeRegistrationPeriodStatus.ACTIVE),
				periodRepository.countByStatus(NoticeRegistrationPeriodStatus.INACTIVE),
				eventRepository.countByNoticeRegistrationPeriodIsNotNull()
		);
	}

	private int linkExistingEvents(PoolNotice notice, List<NoticeRegistrationPeriod> periods) {
		int linked = 0;
		for (NoticeRegistrationPeriod period : periods) {
			if (period.id() == null
					|| eventRepository.findByNoticeRegistrationPeriod_Id(period.id()).isPresent()) {
				continue;
			}
			String title = buildEventTitle(notice, period);
			RegistrationEvent event = eventRepository
					.findByPool_IdAndTitleAndRegistrationStartsAtAndRegistrationEndsAt(
							notice.getPool().getId(),
							title,
							period.startsAt(),
							period.endsAt()
					)
					.orElse(null);
			if (event == null || event.getNoticeRegistrationPeriod() != null) {
				continue;
			}
			NoticeRegistrationPeriodEntity entity = periodRepository.getReferenceById(period.id());
			event.assignNoticeRegistrationPeriod(entity);
			linked++;
		}
		return linked;
	}

	private LegacyParseResult parseLegacyPeriods(PoolNotice notice) {
		if (hasText(notice.getRegistrationPeriodsJson())) {
			try {
				List<NoticeRegistrationPeriod> periods = objectMapper.readValue(
						notice.getRegistrationPeriodsJson(),
						new TypeReference<>() {
						}
				);
				if (periods != null && !periods.isEmpty()) {
					return new LegacyParseResult(periods, false, null);
				}
			} catch (JsonProcessingException exception) {
				if (notice.getRegistrationStartsAt() == null || notice.getRegistrationEndsAt() == null) {
					return new LegacyParseResult(
							List.of(),
							false,
							"registration_periods_json 파싱 실패: " + exception.getOriginalMessage()
					);
				}
				return legacySinglePeriod(notice, "JSON 파싱 실패 후 대표 기간 fallback");
			}
		}
		if (notice.getRegistrationStartsAt() != null && notice.getRegistrationEndsAt() != null) {
			return legacySinglePeriod(notice, "기존 대표 기간 fallback");
		}
		return new LegacyParseResult(List.of(), false, null);
	}

	private LegacyParseResult legacySinglePeriod(PoolNotice notice, String reason) {
		return new LegacyParseResult(
				List.of(new NoticeRegistrationPeriod(
						null,
						notice.getRegistrationStartsAt(),
						notice.getRegistrationEndsAt(),
						null,
						"legacy"
				)),
				true,
				reason
		);
	}

	private Map<PeriodKey, NoticeRegistrationPeriod> normalizeIncoming(List<NoticeRegistrationPeriod> periods) {
		Map<PeriodKey, NoticeRegistrationPeriod> normalized = new LinkedHashMap<>();
		if (periods == null) {
			return normalized;
		}
		for (NoticeRegistrationPeriod period : periods) {
			if (period == null || period.startsAt() == null || period.endsAt() == null) {
				continue;
			}
			if (!period.startsAt().isBefore(period.endsAt())) {
				continue;
			}
			normalized.putIfAbsent(PeriodKey.from(period), period);
		}
		return normalized;
	}

	private String buildEventTitle(PoolNotice notice, NoticeRegistrationPeriod period) {
		String label = hasText(period.label()) ? period.label().trim() : "모집 기간";
		String title = (label + " - " + notice.getTitle()).replaceAll("\\s+", " ").trim();
		return title.length() <= 120 ? title : title.substring(0, 120);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private record LegacyParseResult(
			List<NoticeRegistrationPeriod> periods,
			boolean legacyFallbackUsed,
			String error
	) {
	}

	private record PeriodKey(
			String normalizedLabel,
			Instant startsAt,
			Instant endsAt
	) {
		static PeriodKey from(NoticeRegistrationPeriod period) {
			return new PeriodKey(
					NoticeRegistrationPeriodEntity.normalizeLabel(period.label()),
					period.startsAt(),
					period.endsAt()
			);
		}

		static PeriodKey from(NoticeRegistrationPeriodEntity period) {
			return new PeriodKey(
					period.getNormalizedLabel(),
					period.getStartsAt(),
					period.getEndsAt()
			);
		}
	}
}
