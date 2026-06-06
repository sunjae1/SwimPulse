package com.swimpulse.subscription;

import com.swimpulse.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {
	private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

	private final SubscriptionService subscriptionService;

	public SubscriptionController(SubscriptionService subscriptionService) {
		this.subscriptionService = subscriptionService;
	}

	@GetMapping
	public List<SubscriptionResponse> findSubscriptions(@AuthenticationPrincipal AuthenticatedUser user) {
		log.info("Subscriptions requested. userId={}", user.id());
		return subscriptionService.findByUser(user.id());
	}

	@PostMapping
	public SubscriptionResponse subscribe(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateSubscriptionRequest request) {
		log.info("Subscription requested. userId={} poolId={} startsAt={} endsAt={}",
				user.id(), request.poolId(), request.registrationStartsAt(), request.registrationEndsAt());
		return subscriptionService.subscribe(user.id(), request);
	}

	@PatchMapping("/{subscriptionId}")
	public SubscriptionResponse updateSubscriptionPeriod(
			@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long subscriptionId,
			@Valid @RequestBody UpdateSubscriptionPeriodRequest request
	) {
		log.info("Subscription period update requested. userId={} subscriptionId={} startsAt={} endsAt={}",
				user.id(), subscriptionId, request.registrationStartsAt(), request.registrationEndsAt());
		return subscriptionService.updatePeriod(user.id(), subscriptionId, request);
	}

	@DeleteMapping
	public void unsubscribe(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam Long eventId) {
		log.info("Unsubscription requested. userId={} eventId={}", user.id(), eventId);
		subscriptionService.unsubscribe(user.id(), eventId);
	}
}
