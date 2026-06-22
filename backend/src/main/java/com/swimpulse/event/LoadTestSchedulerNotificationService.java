package com.swimpulse.event;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.notification.NotificationRepository;
import com.swimpulse.notification.NotificationStatus;
import com.swimpulse.notification.UserDevice;
import com.swimpulse.notification.UserDeviceRepository;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import com.swimpulse.subscription.Subscription;
import com.swimpulse.subscription.SubscriptionRepository;
import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "swimpulse.loadtest.enabled", havingValue = "true")
public class LoadTestSchedulerNotificationService {
	private static final int MAX_USERS = 1000;

	private final AppUserRepository userRepository;
	private final PoolRepository poolRepository;
	private final RegistrationEventRepository eventRepository;
	private final SubscriptionRepository subscriptionRepository;
	private final UserDeviceRepository userDeviceRepository;
	private final NotificationRepository notificationRepository;
	private final StringRedisTemplate redisTemplate;
	private final EventScheduler eventScheduler;
	private final String queueKey;

	public LoadTestSchedulerNotificationService(
			AppUserRepository userRepository,
			PoolRepository poolRepository,
			RegistrationEventRepository eventRepository,
			SubscriptionRepository subscriptionRepository,
			UserDeviceRepository userDeviceRepository,
			NotificationRepository notificationRepository,
			StringRedisTemplate redisTemplate,
			EventScheduler eventScheduler,
			@Value("${swimpulse.notification.queue-key:swimpulse:notifications}") String queueKey
	) {
		this.userRepository = userRepository;
		this.poolRepository = poolRepository;
		this.eventRepository = eventRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.userDeviceRepository = userDeviceRepository;
		this.notificationRepository = notificationRepository;
		this.redisTemplate = redisTemplate;
		this.eventScheduler = eventScheduler;
		this.queueKey = queueKey;
	}

	@Transactional
	public LoadTestSchedulerSeedResponse seed(
			int requestedUsers,
			Long poolId,
			String title,
			long startOffsetSeconds,
			long durationMinutes,
			boolean registerDevices
	) {
		int users = Math.max(1, Math.min(requestedUsers, MAX_USERS));
		Pool pool = poolRepository.findById(poolId)
				.orElseThrow(() -> new BadRequestException("Pool not found: " + poolId));
		Instant startsAt = Instant.now().plus(startOffsetSeconds, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MILLIS);
		Instant endsAt = startsAt.plus(Math.max(1, durationMinutes), ChronoUnit.MINUTES);
		String normalizedTitle = normalizeTitle(title);
		RegistrationEvent event = eventRepository
				.findByPool_IdAndTitleAndRegistrationStartsAtAndRegistrationEndsAt(poolId, normalizedTitle, startsAt, endsAt)
				.orElseGet(() -> eventRepository.save(new RegistrationEvent(
						pool,
						normalizedTitle,
						startsAt,
						endsAt,
						calculateStatus(startsAt, endsAt, Instant.now())
				)));

		int subscriptionsCreated = 0;
		int subscriptionsReused = 0;
		int devicesRegistered = 0;
		for (int i = 1; i <= users; i++) {
			String email = "scheduler-loadtest-user-" + i + "@swimpulse.local";
			int userNumber = i;
			AppUser user = userRepository.findByEmail(email)
					.orElseGet(() -> userRepository.save(new AppUser(email, "Scheduler Load Test User " + userNumber, null)));
			if (subscriptionRepository.findByUser_IdAndEvent_Id(user.getId(), event.getId()).isPresent()) {
				subscriptionsReused++;
			} else {
				subscriptionRepository.save(new Subscription(user, event, pool));
				subscriptionsCreated++;
			}
			if (registerDevices) {
				String deviceId = "scheduler-loadtest-device-" + userNumber;
				UserDevice device = userDeviceRepository.findByUser_IdAndDeviceId(user.getId(), deviceId)
						.orElseGet(() -> new UserDevice(user, deviceId, "scheduler-mock-fcm-token-" + userNumber));
				device.updateToken("scheduler-mock-fcm-token-" + userNumber);
				userDeviceRepository.save(device);
				devicesRegistered++;
			}
		}
		long subscriptionCount = subscriptionRepository.countByEvent_Id(event.getId());
		return new LoadTestSchedulerSeedResponse(
				event.getId(),
				pool.getId(),
				normalizedTitle,
				startsAt,
				endsAt,
				users,
				subscriptionCount,
				subscriptionsCreated,
				subscriptionsReused,
				devicesRegistered
		);
	}

	public LoadTestSchedulerStatusResponse tick(Long eventId) {
		eventScheduler.tick();
		return status(eventId);
	}

	@Transactional(readOnly = true)
	public LoadTestSchedulerStatusResponse status(Long eventId) {
		RegistrationEvent event = eventRepository.findById(eventId)
				.orElseThrow(() -> new BadRequestException("Event not found: " + eventId));
		long subscriptionCount = subscriptionRepository.countByEvent_Id(eventId);
		long notificationCount = notificationRepository.countByEvent_Id(eventId);
		long queuedCount = notificationRepository.countByEvent_IdAndStatus(eventId, NotificationStatus.QUEUED);
		long sendingCount = notificationRepository.countByEvent_IdAndStatus(eventId, NotificationStatus.SENDING);
		long sentCount = notificationRepository.countByEvent_IdAndStatus(eventId, NotificationStatus.SENT);
		long failedCount = notificationRepository.countByEvent_IdAndStatus(eventId, NotificationStatus.FAILED);
		Long queueLength = redisTemplate.opsForList().size(queueKey);
		return new LoadTestSchedulerStatusResponse(
				event.getId(),
				event.getStatus(),
				event.isReminderQueued(),
				event.isStartQueued(),
				subscriptionCount,
				notificationCount,
				queuedCount,
				sendingCount,
				sentCount,
				failedCount,
				queueLength == null ? 0 : queueLength
		);
	}

	private EventStatus calculateStatus(Instant startsAt, Instant endsAt, Instant now) {
		if (now.isBefore(startsAt)) {
			return EventStatus.UPCOMING;
		}
		if (now.isBefore(endsAt)) {
			return EventStatus.OPEN;
		}
		return EventStatus.CLOSED;
	}

	private String normalizeTitle(String title) {
		String trimmed = title == null ? "" : title.replaceAll("\\s+", " ").trim();
		if (trimmed.isBlank()) {
			throw new BadRequestException("Event title is required.");
		}
		return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
	}
}
