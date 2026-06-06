package com.swimpulse.mypage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.swimpulse.event.EventResponse;
import com.swimpulse.event.EventStatus;
import com.swimpulse.notification.NotificationResponse;
import com.swimpulse.notification.NotificationService;
import com.swimpulse.notification.NotificationStatus;
import com.swimpulse.notification.NotificationType;
import com.swimpulse.notification.UserDeviceRepository;
import com.swimpulse.subscription.SubscriptionResponse;
import com.swimpulse.subscription.SubscriptionService;
import com.swimpulse.user.UserResponse;
import com.swimpulse.user.UserService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MyPageServiceTests {
	@Mock
	private UserService userService;

	@Mock
	private SubscriptionService subscriptionService;

	@Mock
	private NotificationService notificationService;

	@Mock
	private UserDeviceRepository userDeviceRepository;

	private MyPageService myPageService;

	@BeforeEach
	void setUp() {
		myPageService = new MyPageService(
				userService,
				subscriptionService,
				notificationService,
				userDeviceRepository
		);
	}

	@Test
	void findMyPageAggregatesUserSubscriptionsAndNotifications() {
		Long userId = 7L;
		UserResponse user = new UserResponse(
				userId,
				"swimmer@example.com",
				"수영러",
				null,
				true,
				true,
				Instant.parse("2026-05-01T00:00:00Z"),
				Instant.parse("2026-06-05T01:30:00Z")
		);
		List<SubscriptionResponse> subscriptions = List.of(
				new SubscriptionResponse(
						1L,
						userId,
						null,
						new EventResponse(
								10L,
								101L,
								"강남스포츠센터 수영장",
								"새벽반 모집",
								Instant.parse("2026-06-05T00:00:00Z"),
								Instant.parse("2026-06-05T03:00:00Z"),
								EventStatus.OPEN,
								true,
								true
						),
						Instant.parse("2026-06-04T12:00:00Z")
				),
				new SubscriptionResponse(
						2L,
						userId,
						null,
						new EventResponse(
								11L,
								102L,
								"마포구민체육센터 수영장",
								"주말반 모집",
								Instant.parse("2026-06-06T00:00:00Z"),
								Instant.parse("2026-06-06T03:00:00Z"),
								EventStatus.UPCOMING,
								false,
								false
						),
						Instant.parse("2026-06-04T14:00:00Z")
				)
		);
		List<NotificationResponse> notifications = List.of(
				new NotificationResponse(
						100L,
						userId,
						101L,
						"강남스포츠센터 수영장",
						10L,
						"새벽반 모집",
						NotificationType.REGISTRATION_OPEN,
						NotificationStatus.SENT,
						"지금 접수 시작",
						"새벽반 접수가 시작됐습니다.",
						null,
						1,
						Instant.parse("2026-06-05T00:10:00Z"),
						Instant.parse("2026-06-05T00:10:05Z"),
						null
				),
				new NotificationResponse(
						101L,
						userId,
						102L,
						"마포구민체육센터 수영장",
						11L,
						"주말반 모집",
						NotificationType.REGISTRATION_REMINDER,
						NotificationStatus.QUEUED,
						"곧 접수 시작",
						"주말반 접수가 곧 시작됩니다.",
						null,
						0,
						Instant.parse("2026-06-05T01:10:00Z"),
						null,
						Instant.parse("2026-06-05T01:15:00Z")
				)
		);

		when(userService.findUser(userId)).thenReturn(user);
		when(subscriptionService.findByUser(userId)).thenReturn(subscriptions);
		when(notificationService.findByUser(userId)).thenReturn(notifications);
		when(userDeviceRepository.countByUser_IdAndEnabledTrue(userId)).thenReturn(2L);

		MyPageResponse response = myPageService.findMyPage(userId);

		assertEquals(user, response.user());
		assertEquals(subscriptions, response.subscriptions());
		assertEquals(notifications, response.notifications());
		assertEquals(2, response.metrics().subscriptionCount());
		assertEquals(1L, response.metrics().upcomingSubscriptionCount());
		assertEquals(1L, response.metrics().openSubscriptionCount());
		assertEquals(2, response.metrics().notificationCount());
		assertEquals(1L, response.metrics().unreadNotificationCount());
		assertEquals(2L, response.metrics().activeDeviceCount());
	}
}
