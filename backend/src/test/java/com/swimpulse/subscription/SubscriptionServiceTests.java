package com.swimpulse.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.event.EventStatus;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.event.RegistrationEventResolver;
import com.swimpulse.notice.NoticeRegistrationPeriodRepository;
import com.swimpulse.notice.NoticeRegistrationPeriod;
import com.swimpulse.notice.NoticeRegistrationPeriodEntity;
import com.swimpulse.notice.NoticeRegistrationPeriodStatus;
import com.swimpulse.notice.NoticeExtractionStatus;
import com.swimpulse.notice.PoolNotice;
import com.swimpulse.pool.Pool;
import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTests {
	@Mock
	private SubscriptionRepository subscriptionRepository;

	@Mock
	private AppUserRepository userRepository;

	@Mock
	private RegistrationEventResolver eventResolver;

	@Mock
	private NoticeRegistrationPeriodRepository periodRepository;

	private SubscriptionService subscriptionService;

	@BeforeEach
	void setUp() {
		subscriptionService = new SubscriptionService(
				subscriptionRepository,
				userRepository,
				eventResolver,
				periodRepository
		);
	}

	@Test
	void updatePeriodCreatesNewEventAndReassignsOnlyCurrentSubscription() {
		AppUser user = new AppUser("swimmer@example.com", "수영러", null);
		setField(user, "id", 7L);
		Pool pool = new Pool("강남 수영장", "강남구", "테스트");
		setField(pool, "id", 101L);

		RegistrationEvent currentEvent = new RegistrationEvent(
				pool,
				"새벽반 모집",
				Instant.now().plus(1, ChronoUnit.DAYS),
				Instant.now().plus(1, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS),
				EventStatus.UPCOMING
		);
		setField(currentEvent, "id", 11L);

		Subscription subscription = new Subscription(user, currentEvent);
		setField(subscription, "id", 21L);

		Instant newStartsAt = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		Instant newEndsAt = newStartsAt.plus(3, ChronoUnit.HOURS);
		RegistrationEvent newEvent = new RegistrationEvent(
				pool,
				"오후반 모집",
				newStartsAt,
				newEndsAt,
				EventStatus.UPCOMING
		);
		setField(newEvent, "id", 31L);

		when(subscriptionRepository.findByIdAndUser_Id(21L, 7L)).thenReturn(Optional.of(subscription));
		when(eventResolver.getOrCreate(
				101L,
				"오후반 모집",
				newStartsAt,
				newEndsAt
		)).thenReturn(newEvent);
		when(subscriptionRepository.findByUser_IdAndEvent_Id(7L, 31L)).thenReturn(Optional.empty());

		SubscriptionResponse response = subscriptionService.updatePeriod(
				7L,
				21L,
				new UpdateSubscriptionPeriodRequest("오후반 모집", newStartsAt, newEndsAt)
		);

		assertEquals(31L, response.event().id());
		assertEquals("오후반 모집", response.event().title());
		assertEquals(newStartsAt, response.event().registrationStartsAt());
		assertEquals(newEndsAt, response.event().registrationEndsAt());
		assertSame(subscription.getEvent().getPool(), pool);
		assertEquals(31L, subscription.getEvent().getId());
	}

	@Test
	void subscribeLinksEventToSelectedNoticePeriod() {
		AppUser user = new AppUser("swimmer@example.com", "수영러", null);
		setField(user, "id", 7L);
		Pool pool = new Pool("강남 수영장", "강남구", "테스트");
		setField(pool, "id", 101L);
		PoolNotice notice = new PoolNotice(
				pool,
				"7월 신규 회원 모집",
				"https://example.com/notices/7",
				"본문",
				NoticeExtractionStatus.EXTRACTED,
				0.9,
				null,
				null,
				"테스트"
		);
		setField(notice, "id", 51L);
		Instant startsAt = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		Instant endsAt = startsAt.plus(2, ChronoUnit.DAYS);
		NoticeRegistrationPeriodEntity period = new NoticeRegistrationPeriodEntity(
				notice,
				new NoticeRegistrationPeriod("신규 회원", startsAt, endsAt, "7. 1. ~ 7. 3.", "block")
		);
		setField(period, "id", 61L);
		RegistrationEvent event = new RegistrationEvent(
				pool,
				period,
				"신규 회원 - 7월 신규 회원 모집",
				startsAt,
				endsAt,
				EventStatus.UPCOMING
		);
		setField(event, "id", 71L);

		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		when(periodRepository.findByIdAndStatus(61L, NoticeRegistrationPeriodStatus.ACTIVE))
				.thenReturn(Optional.of(period));
		when(eventResolver.getOrCreateForNoticePeriod(period, "신규 회원 - 7월 신규 회원 모집"))
				.thenReturn(event);
		when(subscriptionRepository.findByUser_IdAndEvent_Id(7L, 71L)).thenReturn(Optional.empty());
		when(subscriptionRepository.save(org.mockito.ArgumentMatchers.any(Subscription.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		SubscriptionResponse response = subscriptionService.subscribe(
				7L,
				new CreateSubscriptionRequest(
						101L,
						"신규 회원 - 7월 신규 회원 모집",
						startsAt,
						endsAt,
						61L
				)
		);

		assertEquals(61L, response.event().noticeRegistrationPeriodId());
		verify(eventResolver).getOrCreateForNoticePeriod(period, "신규 회원 - 7월 신규 회원 모집");
	}

	@Test
	void subscribeCreatesCustomEventWhenPastNoticePeriodIsShiftedToCurrentMonth() {
		AppUser user = new AppUser("swimmer@example.com", "수영러", null);
		setField(user, "id", 7L);
		Pool pool = new Pool("오정레포츠센터수영장", "오정구", "테스트");
		setField(pool, "id", 44L);
		Instant startsAt = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		Instant endsAt = startsAt.plus(4, ChronoUnit.DAYS);
		String title = "재등록 - 6월 회원모집 (이번 달 예상)";
		RegistrationEvent event = new RegistrationEvent(
				pool,
				title,
				startsAt,
				endsAt,
				EventStatus.UPCOMING
		);
		setField(event, "id", 72L);

		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		when(eventResolver.getOrCreate(44L, title, startsAt, endsAt)).thenReturn(event);
		when(subscriptionRepository.findByUser_IdAndEvent_Id(7L, 72L)).thenReturn(Optional.empty());
		when(subscriptionRepository.save(org.mockito.ArgumentMatchers.any(Subscription.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		SubscriptionResponse response = subscriptionService.subscribe(
				7L,
				new CreateSubscriptionRequest(44L, title, startsAt, endsAt, null)
		);

		assertEquals(null, response.event().noticeRegistrationPeriodId());
		assertEquals(startsAt, response.event().registrationStartsAt());
		assertEquals(endsAt, response.event().registrationEndsAt());
		verify(eventResolver).getOrCreate(44L, title, startsAt, endsAt);
		verify(periodRepository, never()).findByIdAndStatus(
				org.mockito.ArgumentMatchers.anyLong(),
				org.mockito.ArgumentMatchers.any(NoticeRegistrationPeriodStatus.class)
		);
	}

	@Test
	void updatePeriodRejectsDuplicateSubscriptionForSameTargetEvent() {
		AppUser user = new AppUser("swimmer@example.com", "수영러", null);
		setField(user, "id", 7L);
		Pool pool = new Pool("강남 수영장", "강남구", "테스트");
		setField(pool, "id", 101L);

		RegistrationEvent currentEvent = new RegistrationEvent(
				pool,
				"새벽반 모집",
				Instant.now().plus(1, ChronoUnit.DAYS),
				Instant.now().plus(1, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS),
				EventStatus.UPCOMING
		);
		setField(currentEvent, "id", 11L);

		RegistrationEvent targetEvent = new RegistrationEvent(
				pool,
				"오후반 모집",
				Instant.now().plus(3, ChronoUnit.DAYS),
				Instant.now().plus(3, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS),
				EventStatus.UPCOMING
		);
		setField(targetEvent, "id", 41L);

		Subscription subscription = new Subscription(user, currentEvent);
		setField(subscription, "id", 21L);
		Subscription existing = new Subscription(user, targetEvent);
		setField(existing, "id", 22L);

		when(subscriptionRepository.findByIdAndUser_Id(21L, 7L)).thenReturn(Optional.of(subscription));
		when(eventResolver.getOrCreate(
				101L,
				"오후반 모집",
				targetEvent.getRegistrationStartsAt(),
				targetEvent.getRegistrationEndsAt()
		)).thenReturn(targetEvent);
		when(subscriptionRepository.findByUser_IdAndEvent_Id(7L, 41L)).thenReturn(Optional.of(existing));

		assertThrows(BadRequestException.class, () -> subscriptionService.updatePeriod(
				7L,
				21L,
				new UpdateSubscriptionPeriodRequest(
						"오후반 모집",
						targetEvent.getRegistrationStartsAt(),
						targetEvent.getRegistrationEndsAt()
				)
		));

		verify(subscriptionRepository, never()).save(org.mockito.ArgumentMatchers.any(Subscription.class));
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
