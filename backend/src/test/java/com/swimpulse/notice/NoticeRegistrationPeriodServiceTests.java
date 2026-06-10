package com.swimpulse.notice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.swimpulse.event.RegistrationEventRepository;
import com.swimpulse.pool.Pool;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NoticeRegistrationPeriodServiceTests {
	@Mock
	private NoticeRegistrationPeriodRepository periodRepository;

	@Mock
	private PoolNoticeRepository noticeRepository;

	@Mock
	private RegistrationEventRepository eventRepository;

	private NoticeRegistrationPeriodService service;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
		service = new NoticeRegistrationPeriodService(
				periodRepository,
				noticeRepository,
				eventRepository,
				objectMapper
		);
	}

	@Test
	void migratesEveryPeriodFromLegacyJsonIntoSeparateRows() {
		PoolNotice notice = noticeWithId(31L, """
				[
				  {
				    "label": "재등록회원",
				    "startsAt": "2026-06-14T15:00:00Z",
				    "endsAt": "2026-06-20T14:59:59Z",
				    "periodText": "매월 15일 ~ 20일",
				    "source": "block"
				  },
				  {
				    "label": "일반등록회원",
				    "startsAt": "2026-06-22T15:00:00Z",
				    "endsAt": "2026-07-07T14:59:59Z",
				    "periodText": "매월 23일 ~ 익월 7일",
				    "source": "block"
				  }
				]
				""");
		when(noticeRepository.findPendingPeriodMigration(any(Pageable.class))).thenReturn(List.of(notice));
		when(periodRepository.findByNotice_IdOrderByStartsAtAscIdAsc(31L)).thenReturn(List.of());

		AtomicLong ids = new AtomicLong(100);
		when(periodRepository.saveAll(any())).thenAnswer(invocation -> {
			List<NoticeRegistrationPeriodEntity> saved = new ArrayList<>();
			for (NoticeRegistrationPeriodEntity entity : (Iterable<NoticeRegistrationPeriodEntity>) invocation.getArgument(0)) {
				if (entity.getId() == null) {
					setField(entity, "id", ids.getAndIncrement());
				}
				saved.add(entity);
			}
			return saved;
		});

		NoticePeriodMigrationResponse response = service.migrateLegacyPeriods(10);

		assertEquals(1, response.migratedNotices());
		assertEquals(2, response.activePeriods());
		assertEquals(0, response.failedNotices());
		assertNotNull(notice.getPeriodsMigratedAt());
	}

	@Test
	void marksMissingPeriodsInactiveInsteadOfDeletingThem() {
		PoolNotice notice = noticeWithId(32L, null);
		NoticeRegistrationPeriodEntity retained = entityWithId(
				201L,
				notice,
				new NoticeRegistrationPeriod(
						"신규 회원",
						Instant.parse("2026-06-24T15:00:00Z"),
						Instant.parse("2026-06-30T14:59:59Z"),
						"매월 25일 ~ 말일",
						"block"
				)
		);
		NoticeRegistrationPeriodEntity disappeared = entityWithId(
				202L,
				notice,
				new NoticeRegistrationPeriod(
						"재등록회원",
						Instant.parse("2026-06-14T15:00:00Z"),
						Instant.parse("2026-06-20T14:59:59Z"),
						"매월 15일 ~ 20일",
						"block"
				)
		);
		when(periodRepository.findByNotice_IdOrderByStartsAtAscIdAsc(32L))
				.thenReturn(List.of(retained, disappeared));
		when(periodRepository.saveAll(any())).thenAnswer(invocation -> {
			List<NoticeRegistrationPeriodEntity> saved = new ArrayList<>();
			((Iterable<NoticeRegistrationPeriodEntity>) invocation.getArgument(0)).forEach(saved::add);
			return saved;
		});

		List<NoticeRegistrationPeriod> result = service.synchronize(notice, List.of(retained.toDto()));

		assertEquals(1, result.size());
		assertEquals(NoticeRegistrationPeriodStatus.ACTIVE, retained.getStatus());
		assertEquals(NoticeRegistrationPeriodStatus.INACTIVE, disappeared.getStatus());
	}

	@Test
	void doesNotResurrectInactivePeriodsFromLegacyJsonAfterMigration() {
		PoolNotice notice = noticeWithId(33L, """
				[
				  {
				    "label": "지난 모집",
				    "startsAt": "2026-05-01T00:00:00Z",
				    "endsAt": "2026-05-02T00:00:00Z",
				    "periodText": "5. 1. ~ 5. 2.",
				    "source": "block"
				  }
				]
				""");
		notice.markPeriodsMigrated();
		when(periodRepository.findByNotice_IdAndStatusOrderByStartsAtAscIdAsc(
				33L,
				NoticeRegistrationPeriodStatus.ACTIVE
		)).thenReturn(List.of());

		assertEquals(List.of(), service.findForResponse(notice));
	}

	private PoolNotice noticeWithId(Long noticeId, String periodsJson) {
		Pool pool = new Pool("테스트 수영장", "서울", "테스트");
		setField(pool, "id", 10L);
		PoolNotice notice = new PoolNotice(
				pool,
				"7월 회원 모집",
				"https://example.com/notices/1",
				"본문",
				NoticeExtractionStatus.EXTRACTED,
				0.9,
				null,
				null,
				"테스트",
				periodsJson
		);
		setField(notice, "id", noticeId);
		return notice;
	}

	private NoticeRegistrationPeriodEntity entityWithId(
			Long id,
			PoolNotice notice,
			NoticeRegistrationPeriod period
	) {
		NoticeRegistrationPeriodEntity entity = new NoticeRegistrationPeriodEntity(notice, period);
		setField(entity, "id", id);
		return entity;
	}

	private static void setField(Object target, String fieldName, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Failed to set field: " + fieldName, exception);
		}
	}
}
