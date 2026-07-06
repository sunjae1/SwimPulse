package com.swimpulse.notification;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.NotFoundException;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.subscription.Subscription;
import com.swimpulse.subscription.SubscriptionRepository;
import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class NotificationService {
	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 100;

	private final NotificationRepository notificationRepository;
	private final SubscriptionRepository subscriptionRepository;
	private final AppUserRepository userRepository;
	private final UserDeviceRepository userDeviceRepository;
	private final NotificationQueuePublisher queuePublisher;
	private final FcmClient fcmClient;
	private final MeterRegistry meterRegistry;
	private final TransactionTemplate transactionTemplate;
	private final int maxAttempts;

	public NotificationService(
			NotificationRepository notificationRepository,
			SubscriptionRepository subscriptionRepository,
			AppUserRepository userRepository,
			UserDeviceRepository userDeviceRepository,
			NotificationQueuePublisher queuePublisher,
			FcmClient fcmClient,
			MeterRegistry meterRegistry,
			TransactionTemplate transactionTemplate,
			@Value("${swimpulse.notification.max-attempts:3}") int maxAttempts
	) {
		this.notificationRepository = notificationRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.userRepository = userRepository;
		this.userDeviceRepository = userDeviceRepository;
		this.queuePublisher = queuePublisher;
		this.fcmClient = fcmClient;
		this.meterRegistry = meterRegistry;
		this.transactionTemplate = transactionTemplate;
		this.maxAttempts = maxAttempts;
	}

	@Transactional
	public int createAndQueueForEvent(RegistrationEvent event, NotificationType type) {
		List<Subscription> subscriptions = subscriptionRepository.findByEvent_Id(event.getId());
		int created = 0;
		int skipped = 0;
		for (Subscription subscription : subscriptions) {
			String dedupeKey = scheduledDedupeKey(subscription.getUser().getId(), event.getId(), type);
			Optional<Notification> existing = notificationRepository.findByDedupeKey(dedupeKey);
			if (existing.isPresent()) {
				skipped++;
				continue;
			}
			Notification notification = notificationRepository.save(createNotification(subscription.getUser(), event, type, dedupeKey));
			queuePublisher.publishAfterCommit(notification.getId());
			meterRegistry.counter("swimpulse.notification.queue.published", "type", type.name()).increment();
			created++;
		}
		log.info("Notifications created for event subscriptions. eventId={} poolId={} type={} created={} skippedDuplicates={}",
				event.getId(), event.getPool().getId(), type, created, skipped);
		return created;
	}

	@Transactional(readOnly = true)
	public NotificationPageResponse findByUser(Long userId, Integer page, Integer size) {
		ensureUserExists(userId);
		Page<Notification> notifications = notificationRepository.findByUser_IdOrderByCreatedAtDesc(
				userId,
				notificationPageRequest(page, size)
		);
		long unreadCount = notificationRepository.countByUser_IdAndReadAtIsNull(userId);
		return NotificationPageResponse.from(notifications, unreadCount);
	}

	@Transactional(readOnly = true)
	public List<NotificationResponse> findRecentByUser(Long userId, int size) {
		return findByUser(userId, 0, size).content();
	}

	@Transactional(readOnly = true)
	public NotificationResponse findOneByUser(Long notificationId, Long userId) {
		Notification notification = notificationRepository.findById(notificationId)
				.orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));
		if (!notification.getUser().getId().equals(userId)) {
			throw new NotFoundException("Notification not found: " + notificationId);
		}
		return NotificationResponse.from(notification);
	}

	@Transactional(readOnly = true)
	public long countByUser(Long userId) {
		ensureUserExists(userId);
		return notificationRepository.countByUser_Id(userId);
	}

	@Transactional(readOnly = true)
	public long countUnreadByUser(Long userId) {
		ensureUserExists(userId);
		return notificationRepository.countByUser_IdAndReadAtIsNull(userId);
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
				.orElseGet(() -> new UserDevice(user, request.deviceId(), request.fcmToken(), request.resolvedPlatform()));
		device.updateToken(request.fcmToken(), request.resolvedPlatform());
		userDeviceRepository.save(device);
		log.info("User device registered. userId={} deviceId={} platform={}", userId, request.deviceId(), request.resolvedPlatform());
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
		queuePublisher.publishAfterCommit(notification.getId());
		meterRegistry.counter("swimpulse.notification.queue.published", "type", notification.getType().name()).increment();
		log.info("Test notification queued. notificationId={} userId={} poolId={}",
				notification.getId(), userId, subscription.getPool().getId());
		return NotificationResponse.from(notification);
	}

	public boolean deliver(Long notificationId) {
		DeliveryWork work = Objects.requireNonNull(transactionTemplate.execute(status -> startDelivery(notificationId)));
		if (work.skip()) {
			return false;
		}
		if (work.deviceTokens().isEmpty()) {
			return false;
		}

		List<String> messageIds = new java.util.ArrayList<>();
		List<String> failures = new java.util.ArrayList<>();
		for (String fcmToken : work.deviceTokens()) {
			try {
				messageIds.add(fcmClient.send(new FcmMessage(
						fcmToken,
						work.title(),
						work.message(),
						Map.of(
								"notificationId", work.notificationId().toString(),
								"eventId", work.eventId().toString(),
								"poolId", work.poolId().toString(),
								"poolName", work.poolName(),
								"eventTitle", work.eventTitle(),
								"noticeUrl", work.noticeUrl() == null ? "" : work.noticeUrl(),
								"type", work.type().name()
						)
				)));
			} catch (RuntimeException exception) {
				failures.add(exception.getMessage());
				log.warn("Notification delivery failed for one device. notificationId={} message={}",
						notificationId, exception.getMessage());
			}
		}

		if (!messageIds.isEmpty()) {
			transactionTemplate.executeWithoutResult(status ->
					markDeliverySent(notificationId, messageIds.size() + " device(s): " + messageIds.get(0), failures.size()));
			return false;
		}

		Boolean shouldRetry = transactionTemplate.execute(status ->
				markDeliveryFailed(notificationId, String.join("; ", failures), failures.size()));
		return Boolean.TRUE.equals(shouldRetry);
	}

	private DeliveryWork startDelivery(Long notificationId) {
		Notification notification = notificationRepository.findById(notificationId)
				.orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));
		if (notification.getStatus() == NotificationStatus.SENT) {
			log.info("Notification delivery skipped because already sent. notificationId={}", notificationId);
			return DeliveryWork.skip(notificationId);
		}
		if (!notification.markSending()) {
			log.info("Notification delivery skipped because status is not queued. notificationId={} status={}",
					notificationId, notification.getStatus());
			return DeliveryWork.skip(notificationId);
		}

		log.info("Notification delivery started. notificationId={} attempt={}",
				notificationId, notification.getAttempts());
		List<String> deviceTokens = userDeviceRepository.findByUser_IdAndEnabledTrue(notification.getUser().getId())
				.stream()
				.filter(device -> StringUtils.hasText(device.getFcmToken()))
				.map(UserDevice::getFcmToken)
				.toList();
		if (deviceTokens.isEmpty()) {
			notification.markFailed("FCM token is not registered.");
			meterRegistry.counter("swimpulse.notification.delivery", "result", "failed", "type", notification.getType().name()).increment();
			log.warn("Notification delivery failed because no enabled FCM devices exist. notificationId={} userId={}",
					notificationId, notification.getUser().getId());
			return DeliveryWork.skip(notificationId);
		}

		return new DeliveryWork(
				notification.getId(),
				notification.getPool().getId(),
				notification.getPool().getName(),
				notification.getEvent().getId(),
				notification.getEvent().getTitle(),
				noticeUrl(notification),
				notification.getType(),
				notification.getTitle(),
				notification.getMessage(),
				deviceTokens,
				false
		);
	}

	private void markDeliverySent(Long notificationId, String fcmMessageId, int failedDevices) {
		Notification notification = notificationRepository.findById(notificationId)
				.orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));
		if (notification.getStatus() == NotificationStatus.SENT) {
			return;
		}
		notification.markSent(fcmMessageId);
		recordDeliveryLag(notification);
		meterRegistry.counter("swimpulse.notification.delivery", "result", "sent", "type", notification.getType().name()).increment();
		log.info("Notification delivery succeeded. notificationId={} failedDevices={}",
				notificationId, failedDevices);
	}

	private String noticeUrl(Notification notification) {
		if (notification.getEvent().getNoticeRegistrationPeriod() == null
				|| notification.getEvent().getNoticeRegistrationPeriod().getNotice() == null) {
			return notification.getEvent().getNoticeUrl();
		}
		return notification.getEvent().getNoticeRegistrationPeriod().getNotice().getUrl();
	}

	private boolean markDeliveryFailed(Long notificationId, String failureReason, int failedDevices) {
		Notification notification = notificationRepository.findById(notificationId)
				.orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));
		notification.markFailed(failureReason);
		meterRegistry.counter("swimpulse.notification.delivery", "result", "failed", "type", notification.getType().name()).increment();
		log.warn("Notification delivery failed for all devices. notificationId={} failedDevices={} shouldRetry={}",
				notificationId, failedDevices, notification.getAttempts() < maxAttempts);
		return notification.getAttempts() < maxAttempts;
	}

	@Transactional
	public void requeue(Long notificationId) {
		Notification notification = notificationRepository.findById(notificationId)
				.orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));
		notification.markQueued();
		queuePublisher.publishAfterCommit(notificationId);
		meterRegistry.counter("swimpulse.notification.queue.requeued", "reason", "retry").increment();
		log.info("Notification requeued. notificationId={} attempts={}", notificationId, notification.getAttempts());
	}

	@Transactional
	public NotificationResponse requeueFailed(Long notificationId) {
		Notification notification = notificationRepository.findById(notificationId)
				.orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));
		if (notification.getStatus() != NotificationStatus.FAILED) {
			throw new BadRequestException("Only FAILED notifications can be manually requeued.");
		}
		notification.markQueued();
		queuePublisher.publishAfterCommit(notificationId);
		meterRegistry.counter("swimpulse.notification.queue.requeued", "reason", "admin_failed").increment();
		log.info("Failed notification manually requeued. notificationId={} attempts={}", notificationId, notification.getAttempts());
		return NotificationResponse.from(notification);
	}

	@Transactional(readOnly = true)
	public List<NotificationResponse> findFailed(int limit) {
		int normalizedLimit = Math.max(1, Math.min(limit, 50));
		return notificationRepository.findByStatusOrderByCreatedAtDesc(
						NotificationStatus.FAILED,
						PageRequest.of(0, normalizedLimit)
				)
				.stream()
				.map(NotificationResponse::from)
				.toList();
	}

	@Transactional
	public int requeueStaleSending(Duration staleTimeout) {
		Instant cutoff = Instant.now().minus(staleTimeout);
		List<Notification> staleNotifications = notificationRepository
				.findStaleByStatus(NotificationStatus.SENDING, cutoff, PageRequest.of(0, 50));
		for (Notification notification : staleNotifications) {
			Instant processingStartedAt = notification.getProcessingStartedAt();
			notification.markQueued();
			queuePublisher.publishAfterCommit(notification.getId());
			meterRegistry.counter("swimpulse.notification.queue.requeued", "reason", "stale_sending").increment();
			log.warn("Stale sending notification requeued. notificationId={} processingStartedAt={} attempts={}",
					notification.getId(), processingStartedAt, notification.getAttempts());
		}
		return staleNotifications.size();
	}

	@Transactional
	public int requeueStaleSending(Duration staleTimeout, int limit) {
		int normalizedLimit = Math.max(1, Math.min(limit, 50));
		Instant cutoff = Instant.now().minus(staleTimeout);
		List<Notification> staleNotifications = notificationRepository
				.findStaleByStatus(
						NotificationStatus.SENDING,
						cutoff,
						PageRequest.of(0, normalizedLimit)
				);
		for (Notification notification : staleNotifications) {
			Instant processingStartedAt = notification.getProcessingStartedAt();
			notification.markQueued();
			queuePublisher.publishAfterCommit(notification.getId());
			meterRegistry.counter("swimpulse.notification.queue.requeued", "reason", "admin_stale_sending").increment();
			log.warn("Stale sending notification manually requeued. notificationId={} processingStartedAt={} attempts={}",
					notification.getId(), processingStartedAt, notification.getAttempts());
		}
		return staleNotifications.size();
	}

	private Notification createNotification(AppUser user, RegistrationEvent event, NotificationType type, String dedupeKey) {
		String title = switch (type) {
			case REGISTRATION_REMINDER -> "수영장 접수 시작이 곧 다가옵니다";
			case REGISTRATION_OPEN -> "지금 수영장 접수가 시작됐습니다";
		};
		String message = switch (type) {
			case REGISTRATION_REMINDER -> event.getPool().getName() + " " + event.getTitle() + " 접수가 곧 시작됩니다.";
			case REGISTRATION_OPEN -> event.getPool().getName() + " " + event.getTitle() + " 접수가 시작됐습니다. 지금 확인하세요.";
		};
		return new Notification(user, event.getPool(), event, type, title, message, dedupeKey);
	}

	private String scheduledDedupeKey(Long userId, Long eventId, NotificationType type) {
		return userId + ":" + eventId + ":" + type.name();
	}

	private void recordDeliveryLag(Notification notification) {
		if (notification.getCreatedAt() == null || notification.getSentAt() == null) {
			return;
		}
		Timer.builder("swimpulse.notification.delivery.lag")
				.description("Elapsed time between notification creation and successful FCM delivery")
				.tag("type", notification.getType().name())
				.register(meterRegistry)
				.record(Duration.between(notification.getCreatedAt(), notification.getSentAt()));
	}

	private void ensureUserExists(Long userId) {
		if (!userRepository.existsById(userId)) {
			throw new NotFoundException("User not found: " + userId);
		}
	}

	private PageRequest notificationPageRequest(Integer page, Integer size) {
		int safePage = page == null ? 0 : Math.max(0, page);
		int safeSize = size == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(size, MAX_PAGE_SIZE));
		return PageRequest.of(safePage, safeSize);
	}

	public record DeliveryWork(
			Long notificationId,
			Long poolId,
			String poolName,
			Long eventId,
			String eventTitle,
			String noticeUrl,
			NotificationType type,
			String title,
			String message,
			List<String> deviceTokens,
			boolean skip
	) {
		public static DeliveryWork skip(Long notificationId) {
			return new DeliveryWork(notificationId, null, null, null, null, null, null, null, null, List.of(), true);
		}
	}
}
