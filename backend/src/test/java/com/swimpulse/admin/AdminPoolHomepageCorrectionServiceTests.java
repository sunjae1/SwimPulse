package com.swimpulse.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swimpulse.event.EventSourceValidityStatus;
import com.swimpulse.event.EventStatus;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.notice.NoticeSourceStatus;
import com.swimpulse.notice.NoticeSourceType;
import com.swimpulse.notice.PoolNoticeSource;
import com.swimpulse.notice.PoolNoticeSourceRepository;
import com.swimpulse.notification.NotificationService;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import com.swimpulse.subscription.Subscription;
import com.swimpulse.subscription.SubscriptionRepository;
import com.swimpulse.subscription.SubscriptionReviewStatus;
import com.swimpulse.user.AppUser;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminPoolHomepageCorrectionServiceTests {
	@Mock private PoolRepository poolRepository;
	@Mock private PoolNoticeSourceRepository sourceRepository;
	@Mock private SubscriptionRepository subscriptionRepository;
	@Mock private NotificationService notificationService;

	private AdminPoolHomepageCorrectionService service;

	@BeforeEach
	void setUp() {
		service = new AdminPoolHomepageCorrectionService(
				poolRepository,
				sourceRepository,
				subscriptionRepository,
				notificationService
		);
	}

	@Test
	void correctMovesAffectedDataToReviewWithoutDeletingSubscriptions() {
		Pool pool = new Pool("잘못된 시설명", "서울", "테스트");
		setField(pool, "id", 132L);
		pool.updateHomepageUrl("https://old.example.com");
		PoolNoticeSource source = new PoolNoticeSource(pool, "https://old.example.com/notices", NoticeSourceType.NOTICE_PAGE);
		source.markVerified();

		AppUser user = new AppUser("swimmer@example.com", "수영러", null);
		setField(user, "id", 7L);
		RegistrationEvent event = new RegistrationEvent(
				pool,
				"https://old.example.com/notices/1",
				"7월 모집",
				Instant.now().plus(1, ChronoUnit.DAYS),
				Instant.now().plus(2, ChronoUnit.DAYS),
				EventStatus.UPCOMING
		);
		setField(event, "id", 77L);
		Subscription subscription = new Subscription(user, event);
		setField(subscription, "id", 88L);

		when(poolRepository.findById(132L)).thenReturn(Optional.of(pool));
		when(sourceRepository.findByPoolOrderByIdAsc(pool)).thenReturn(List.of(source));
		when(subscriptionRepository.findByPool_Id(132L)).thenReturn(List.of(subscription));
		when(notificationService.cancelQueuedForSubscriptions(List.of(subscription))).thenReturn(2);
		when(notificationService.createSourceReviewNotifications(List.of(subscription), 2)).thenReturn(1);

		AdminPoolHomepageCorrectionResponse response = service.correct(
				132L,
				new AdminPoolHomepageCorrectionRequest(
						"올바른 시설명",
						"https://new.example.com",
						"출처 교정"
				)
		);

		assertEquals(2, pool.getHomepageRevision());
		assertEquals("올바른 시설명", pool.getName());
		assertEquals("https://new.example.com", pool.getHomepageUrl());
		assertNull(pool.getLastNoticeDiscoveryAt());
		assertEquals(NoticeSourceStatus.INACTIVE, source.getStatus());
		assertEquals(EventSourceValidityStatus.REVIEW_REQUIRED, event.getSourceValidityStatus());
		assertEquals(SubscriptionReviewStatus.REVIEW_REQUIRED, subscription.getReviewStatus());
		assertEquals(1, response.reviewRequiredSubscriptions());
		assertEquals(2, response.cancelledNotifications());
		verify(notificationService).createSourceReviewNotifications(List.of(subscription), 2);
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
