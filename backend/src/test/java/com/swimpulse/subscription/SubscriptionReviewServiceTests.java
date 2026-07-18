package com.swimpulse.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swimpulse.event.EventStatus;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.notification.NotificationService;
import com.swimpulse.pool.Pool;
import com.swimpulse.user.AppUser;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubscriptionReviewServiceTests {
	@Mock private SubscriptionRepository subscriptionRepository;
	@Mock private NotificationService notificationService;

	@Test
	void confirmCurrentPeriodResumesCancelledNotifications() {
		Pool pool = new Pool("테스트 수영장", "서울", "테스트");
		AppUser user = new AppUser("swimmer@example.com", "수영러", null);
		RegistrationEvent event = new RegistrationEvent(
				pool,
				"https://old.example.com/notice",
				"모집",
				Instant.now().plus(1, ChronoUnit.DAYS),
				Instant.now().plus(2, ChronoUnit.DAYS),
				EventStatus.UPCOMING
		);
		Subscription subscription = new Subscription(user, event);
		setField(subscription, "id", 44L);
		subscription.requireReview("출처 변경");
		when(subscriptionRepository.findByIdAndUser_Id(44L, 7L)).thenReturn(Optional.of(subscription));

		SubscriptionReviewService service = new SubscriptionReviewService(subscriptionRepository, notificationService);
		SubscriptionResponse response = service.confirmCurrentPeriod(7L, 44L);

		assertEquals(SubscriptionReviewStatus.CONFIRMED, response.reviewStatus());
		verify(notificationService).resumeCancelledForSubscription(44L);
	}

	private static void setField(Object target, String fieldName, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
