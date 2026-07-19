package com.swimpulse.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swimpulse.event.EventStatus;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.pool.Pool;
import com.swimpulse.subscription.Subscription;
import com.swimpulse.subscription.SubscriptionRepository;
import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class NotificationSourceReviewTests {
	@Mock private NotificationRepository notificationRepository;
	@Mock private SubscriptionRepository subscriptionRepository;
	@Mock private AppUserRepository userRepository;
	@Mock private UserDeviceRepository userDeviceRepository;
	@Mock private NotificationQueuePublisher queuePublisher;
	@Mock private FcmClient fcmClient;
	@Mock private TransactionTemplate transactionTemplate;

	private NotificationService service;
	private Subscription subscription;
	private RegistrationEvent event;

	@BeforeEach
	void setUp() {
		Pool pool = new Pool("테스트 수영장", "서울", "테스트");
		setField(pool, "id", 10L);
		pool.updateHomepageUrl("https://new.example.com");
		AppUser user = new AppUser("swimmer@example.com", "수영러", null);
		setField(user, "id", 20L);
		event = new RegistrationEvent(
				pool,
				"https://old.example.com/notice",
				"7월 모집",
				Instant.parse("2026-07-20T03:00:00Z"),
				Instant.parse("2026-07-21T03:00:00Z"),
				EventStatus.UPCOMING
		);
		setField(event, "id", 30L);
		subscription = new Subscription(user, event);
		setField(subscription, "id", 40L);

		service = new NotificationService(
				notificationRepository,
				subscriptionRepository,
				userRepository,
				userDeviceRepository,
				queuePublisher,
				fcmClient,
				new SimpleMeterRegistry(),
				transactionTemplate,
				3
		);
	}

	@Test
	void reminderMessageUsesRegistrationStartInsteadOfNotificationCreatedTime() {
		when(subscriptionRepository.findByEvent_Id(30L)).thenReturn(List.of(subscription));
		when(notificationRepository.findByDedupeKey("20:30:REGISTRATION_REMINDER"))
				.thenReturn(Optional.empty());
		when(notificationRepository.save(org.mockito.ArgumentMatchers.any(Notification.class)))
				.thenAnswer(invocation -> {
					Notification notification = invocation.getArgument(0);
					setField(notification, "id", 60L);
					return notification;
				});

		assertEquals(1, service.createAndQueueForEvent(event, NotificationType.REGISTRATION_REMINDER));

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository).save(captor.capture());
		Notification saved = captor.getValue();
		assertEquals(
				"테스트 수영장 7월 모집 접수가 7월 20일 오후 12시에 시작합니다.",
				saved.getMessage()
		);
		assertEquals(
				Instant.parse("2026-07-20T03:00:00Z"),
				NotificationResponse.from(saved).registrationStartsAt()
		);
		verify(queuePublisher).publishAfterCommit(60L);
	}

	@Test
	void cancelQueuedKeepsHistoryAndChangesOnlyQueuedDeliveryStatus() {
		Notification queued = new Notification(
				subscription,
				NotificationType.REGISTRATION_REMINDER,
				"리마인더",
				"곧 시작",
				"scheduled"
		);
		when(notificationRepository.findBySubscription_IdInAndStatus(List.of(40L), NotificationStatus.QUEUED))
				.thenReturn(List.of(queued));

		assertEquals(1, service.cancelQueuedForSubscriptions(List.of(subscription)));
		assertEquals(NotificationStatus.CANCELLED, queued.getStatus());
	}

	@Test
	void sourceReviewNotificationLinksSubscriptionAndUsesRevisionDedupeKey() {
		when(notificationRepository.save(org.mockito.ArgumentMatchers.any(Notification.class)))
				.thenAnswer(invocation -> {
					Notification notification = invocation.getArgument(0);
					setField(notification, "id", 50L);
					return notification;
				});

		assertEquals(1, service.createSourceReviewNotifications(List.of(subscription), 2));

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository).save(captor.capture());
		Notification saved = captor.getValue();
		assertEquals(NotificationType.SOURCE_REVIEW_REQUIRED, saved.getType());
		assertEquals("source-review:40:2", saved.getDedupeKey());
		assertSame(subscription, saved.getSubscription());
		verify(queuePublisher).publishAfterCommit(50L);
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
