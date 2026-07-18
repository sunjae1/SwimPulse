package com.swimpulse.subscription;

import com.swimpulse.common.NotFoundException;
import com.swimpulse.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionReviewService {
	private final SubscriptionRepository subscriptionRepository;
	private final NotificationService notificationService;

	public SubscriptionReviewService(
			SubscriptionRepository subscriptionRepository,
			NotificationService notificationService
	) {
		this.subscriptionRepository = subscriptionRepository;
		this.notificationService = notificationService;
	}

	@Transactional
	public SubscriptionResponse confirmCurrentPeriod(Long userId, Long subscriptionId) {
		Subscription subscription = subscriptionRepository.findByIdAndUser_Id(subscriptionId, userId)
				.orElseThrow(() -> new NotFoundException("Subscription not found: " + subscriptionId));
		subscription.confirmReview();
		notificationService.resumeCancelledForSubscription(subscriptionId);
		return SubscriptionResponse.from(subscription);
	}
}
