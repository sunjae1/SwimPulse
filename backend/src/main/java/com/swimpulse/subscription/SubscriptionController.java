package com.swimpulse.subscription;

import com.swimpulse.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {
	private final SubscriptionService subscriptionService;

	public SubscriptionController(SubscriptionService subscriptionService) {
		this.subscriptionService = subscriptionService;
	}

	@GetMapping
	public List<SubscriptionResponse> findSubscriptions(@AuthenticationPrincipal AuthenticatedUser user) {
		return subscriptionService.findByUser(user.id());
	}

	@PostMapping
	public SubscriptionResponse subscribe(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateSubscriptionRequest request) {
		return subscriptionService.subscribe(user.id(), request);
	}

	@DeleteMapping
	public void unsubscribe(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam Long poolId) {
		subscriptionService.unsubscribe(user.id(), poolId);
	}
}
