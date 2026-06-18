package com.swimpulse.notification;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.NotFoundException;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.subscription.Subscription;
import com.swimpulse.subscription.SubscriptionRepository;
import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class NotificationService {
	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final NotificationRepository notificationRepository;
	private final SubscriptionRepository subscriptionRepository;
	private final AppUserRepository userRepository;
	private final UserDeviceRepository userDeviceRepository;
	private final NotificationQueuePublisher queuePublisher;
	private final FcmClient fcmClient;
	private final int maxAttempts;

	public NotificationService(
			NotificationRepository notificationRepository,
			SubscriptionRepository subscriptionRepository,
			AppUserRepository userRepository,
			UserDeviceRepository userDeviceRepository,
			NotificationQueuePublisher queuePublisher,
			FcmClient fcmClient,
			@Value("${swimpulse.notification.max-attempts:3}") int maxAttempts
	) {
		this.notificationRepository = notificationRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.userRepository = userRepository;
		this.userDeviceRepository = userDeviceRepository;
		this.queuePublisher = queuePublisher;
		this.fcmClient = fcmClient;
		this.maxAttempts = maxAttempts;
	}

	@Transactional
	public int createAndQueueForEvent(RegistrationEvent event, NotificationType type) {
		List<Subscription> subscriptions = subscriptionRepository.findByEvent_Id(event.getId());
		for (Subscription subscription : subscriptions) {
			Notification notification = notificationRepository.save(createNotification(subscription.getUser(), event, type));
			queuePublisher.publish(notification.getId());
		}
		log.info("Notifications created for event subscriptions. eventId={} poolId={} type={} count={}",
				event.getId(), event.getPool().getId(), type, subscriptions.size());
		return subscriptions.size();
	}

	@Transactional(readOnly = true)
	public List<NotificationResponse> findByUser(Long userId) {
		ensureUserExists(userId);
		return notificationRepository.findByUser_IdOrderByCreatedAtDesc(userId)
				.stream()
				.map(NotificationResponse::from)
				.toList();
	}

	@Transactional
	public NotificationResponse markRead(Long notificationId, Long userId) {
		Notification notification = notificationRepository.findById(notificationId)
				.orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));
		if (!notification.getUser().getId().equals(userId)) {
			throw new BadRequestException("Notification does not belong to user: " + userId);
		}
		if (notification.getReadAt() == null) {
			notification.markRead();
			log.info("Notification marked read. notificationId={} userId={}", notificationId, userId);
		} else {
			log.info("Notification read request ignored because already read. notificationId={} userId={} readAt={}",
					notificationId, userId, notification.getReadAt());
		}
		return NotificationResponse.from(notification);
	}

	@Transactional
	public void registerDeviceToken(Long userId, RegisterDeviceTokenRequest request) {
		AppUser user = userRepository.findById(userId)
				.orElseThrow(() -> new NotFoundException("User not found: " + userId));
		UserDevice device = userDeviceRepository.findByUser_IdAndDeviceId(userId, request.deviceId())
				.orElseGet(() -> new UserDevice(user, request.deviceId(), request.fcmToken()));
		device.updateToken(request.fcmToken());
		userDeviceRepository.save(device);
		log.info("User device registered. userId={} deviceId={}", userId, request.deviceId());
	}

	@Transactional(readOnly = true)
	public DeviceRegistrationResponse findDeviceRegistration(Long userId, String deviceId) {
		ensureUserExists(userId);
		return userDeviceRepository.findByUser_IdAndDeviceId(userId, deviceId)
				.map(device -> DeviceRegistrationResponse.from(device, deviceId))
				.orElseGet(() -> DeviceRegistrationResponse.unregistered(deviceId));
	}

	@Transactional
	public void unregisterDevice(Long userId, String deviceId) {
		UserDevice device = userDeviceRepository.findByUser_IdAndDeviceId(userId, deviceId)
				.orElseThrow(() -> new NotFoundException("Device is not registered."));
		device.disable();
		log.info("User device disabled. userId={} deviceId={}", userId, deviceId);
	}

	@Transactional
	public NotificationResponse queueTestNotification(Long userId) {
		AppUser user = userRepository.findById(userId)
				.orElseThrow(() -> new NotFoundException("User not found: " + userId));
		if (!userDeviceRepository.existsByUser_IdAndEnabledTrue(userId)) {
			throw new BadRequestException("Register web push before sending a test notification.");
		}
		Subscription subscription = subscriptionRepository.findByUser_IdOrderByCreatedAtDesc(userId)
				.stream()
				.filter(candidate -> candidate.getEvent() != null)
				.findFirst()
				.orElseThrow(() -> new BadRequestException("Subscribe to a registration period before sending a test notification."));
		RegistrationEvent event = subscription.getEvent();
		Notification notification = notificationRepository.save(new Notification(
				user,
				subscription.getPool(),
				event,
				NotificationType.REGISTRATION_OPEN,
				"SwimPulse 테스트 푸시",
				subscription.getPool().getName() + " 테스트 알림입니다. 실제 FCM 설정이 연결되어 있으면 브라우저 푸시로 도착합니다."
		));
		queuePublisher.publish(notification.getId());
		log.info("Test notification queued. notificationId={} userId={} poolId={}",
				notification.getId(), userId, subscription.getPool().getId());
		return NotificationResponse.from(notification);
	}

	@Transactional
	public boolean deliver(Long notificationId) {
		Notification notification = notificationRepository.findById(notificationId)
				.orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));
		if (notification.getStatus() == NotificationStatus.SENT) {
			log.info("Notification delivery skipped because already sent. notificationId={}", notificationId);
			return false;
		}

		notification.recordAttempt();
		log.info("Notification delivery started. notificationId={} attempt={}",
				notificationId, notification.getAttempts());
		List<UserDevice> devices = userDeviceRepository.findByUser_IdAndEnabledTrue(notification.getUser().getId())
				.stream()
				.filter(device -> StringUtils.hasText(device.getFcmToken()))
				.toList();
		if (devices.isEmpty()) {
			notification.markFailed("FCM token is not registered.");
			log.warn("Notification delivery failed because no enabled FCM devices exist. notificationId={} userId={}",
					notificationId, notification.getUser().getId());
			return false;
		}

		List<String> messageIds = new java.util.ArrayList<>();
		List<String> failures = new java.util.ArrayList<>();
		for (UserDevice device : devices) {
			try {
				messageIds.add(fcmClient.send(new FcmMessage(
						device.getFcmToken(),
						notification.getTitle(),
						notification.getMessage(),
						Map.of(
								"notificationId", notification.getId().toString(),
								"eventId", notification.getEvent().getId().toString(),
								"poolId", notification.getPool().getId().toString(),
								"poolName", notification.getPool().getName(),
								"eventTitle", notification.getEvent().getTitle(),
								"type", notification.getType().name()
						)
				)));
			} catch (RuntimeException exception) {
				failures.add(exception.getMessage());
				log.warn("Notification delivery failed for one device. notificationId={} message={}",
						notificationId, exception.getMessage());
			}
		}

		if (!messageIds.isEmpty()) {
			notification.markSent(messageIds.size() + " device(s): " + messageIds.get(0));
			log.info("Notification delivery succeeded. notificationId={} deliveredDevices={} failedDevices={}",
					notificationId, messageIds.size(), failures.size());
			return false;
		}

		notification.markFailed(String.join("; ", failures));
		log.warn("Notification delivery failed for all devices. notificationId={} failedDevices={} shouldRetry={}",
				notificationId, failures.size(), notification.getAttempts() < maxAttempts);
		return notification.getAttempts() < maxAttempts;
	}

	@Transactional
	public void requeue(Long notificationId) {
		Notification notification = notificationRepository.findById(notificationId)
				.orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));
		notification.markQueued();
		queuePublisher.publish(notificationId);
		log.info("Notification requeued. notificationId={} attempts={}", notificationId, notification.getAttempts());
	}

	private Notification createNotification(AppUser user, RegistrationEvent event, NotificationType type) {
		String title = switch (type) {
			case REGISTRATION_REMINDER -> "수영장 접수 시작이 곧 다가옵니다";
			case REGISTRATION_OPEN -> "지금 수영장 접수가 시작됐습니다";
		};
		String message = switch (type) {
			case REGISTRATION_REMINDER -> event.getPool().getName() + " " + event.getTitle() + " 접수가 곧 시작됩니다.";
			case REGISTRATION_OPEN -> event.getPool().getName() + " " + event.getTitle() + " 접수가 시작됐습니다. 지금 확인하세요.";
		};
		return new Notification(user, event.getPool(), event, type, title, message);
	}

	private void ensureUserExists(Long userId) {
		if (!userRepository.existsById(userId)) {
			throw new NotFoundException("User not found: " + userId);
		}
	}
}
