package com.swimpulse.notification;

import com.swimpulse.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
	private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

	private final NotificationService notificationService;

	public NotificationController(NotificationService notificationService) {
		this.notificationService = notificationService;
	}

	@GetMapping
	public List<NotificationResponse> findNotifications(@AuthenticationPrincipal AuthenticatedUser user) {
		log.info("Notifications requested. userId={}", user.id());
		return notificationService.findByUser(user.id());
	}

	@PatchMapping("/{notificationId}/read")
	public NotificationResponse markRead(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long notificationId) {
		log.info("Notification read requested. userId={} notificationId={}", user.id(), notificationId);
		return notificationService.markRead(notificationId, user.id());
	}

	@PostMapping("/device-tokens")
	public ResponseEntity<Void> registerDeviceToken(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody RegisterDeviceTokenRequest request) {
		log.info("Device token registration requested. userId={} deviceId={}", user.id(), request.deviceId());
		notificationService.registerDeviceToken(user.id(), request);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/device-tokens/current")
	public DeviceRegistrationResponse findCurrentDevice(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam String deviceId) {
		log.info("Current device registration requested. userId={} deviceId={}", user.id(), deviceId);
		return notificationService.findDeviceRegistration(user.id(), deviceId);
	}

	@DeleteMapping("/device-tokens/current")
	public ResponseEntity<Void> unregisterCurrentDevice(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam String deviceId) {
		log.info("Current device unregister requested. userId={} deviceId={}", user.id(), deviceId);
		notificationService.unregisterDevice(user.id(), deviceId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/test")
	public NotificationResponse sendTestNotification(@AuthenticationPrincipal AuthenticatedUser user) {
		log.info("Test notification requested. userId={}", user.id());
		return notificationService.queueTestNotification(user.id());
	}
}
