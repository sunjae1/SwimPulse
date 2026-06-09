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

	private SubscriptionService subscriptionService;

	@BeforeEach
	void setUp() {
		subscriptionService = new SubscriptionService(
				subscriptionRepository,
				userRepository,
				eventResolver
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
