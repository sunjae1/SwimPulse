package com.swimpulse.mypage;

import com.swimpulse.notification.NotificationResponse;
import com.swimpulse.notification.NotificationService;
import com.swimpulse.notification.UserDeviceRepository;
import com.swimpulse.subscription.SubscriptionResponse;
import com.swimpulse.subscription.SubscriptionService;
import com.swimpulse.user.UserResponse;
import com.swimpulse.user.UserService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyPageService {
	private final UserService userService;
	private final SubscriptionService subscriptionService;
	private final NotificationService notificationService;
	private final UserDeviceRepository userDeviceRepository;

	public MyPageService(
			UserService userService,
			SubscriptionService subscriptionService,
			NotificationService notificationService,
			UserDeviceRepository userDeviceRepository
	) {
		this.userService = userService;
		this.subscriptionService = subscriptionService;
		this.notificationService = notificationService;
		this.userDeviceRepository = userDeviceRepository;
	}

	@Transactional(readOnly = true)
	public MyPageResponse findMyPage(Long userId) {
		UserResponse user = userService.findUser(userId);
		List<SubscriptionResponse> subscriptions = subscriptionService.findByUser(userId);
		List<NotificationResponse> notifications = notificationService.findByUser(userId);
		long activeDeviceCount = userDeviceRepository.countByUser_IdAndEnabledTrue(userId);
		return MyPageResponse.from(user, subscriptions, notifications, activeDeviceCount);
	}
}
