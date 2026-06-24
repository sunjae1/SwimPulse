package com.swimpulse.admin;

import com.swimpulse.event.EventStatus;
import com.swimpulse.event.RegistrationEventRepository;
import com.swimpulse.notice.NoticeExtractionStatus;
import com.swimpulse.notice.NoticeOcrStatus;
import com.swimpulse.notice.NoticeSourceStatus;
import com.swimpulse.notice.PoolNoticeRepository;
import com.swimpulse.notice.PoolNoticeSourceRepository;
import com.swimpulse.notification.NotificationRepository;
import com.swimpulse.notification.NotificationResponse;
import com.swimpulse.notification.NotificationStatus;
import com.swimpulse.notification.UserDeviceRepository;
import com.swimpulse.pool.PoolAddRequestResponse;
import com.swimpulse.pool.PoolAddRequestService;
import com.swimpulse.pool.PoolRepository;
import com.swimpulse.subscription.SubscriptionRepository;
import com.swimpulse.user.AppUserRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {
	private final AppUserRepository userRepository;
	private final PoolRepository poolRepository;
	private final SubscriptionRepository subscriptionRepository;
	private final RegistrationEventRepository eventRepository;
	private final NotificationRepository notificationRepository;
	private final UserDeviceRepository userDeviceRepository;
	private final PoolNoticeSourceRepository noticeSourceRepository;
	private final PoolNoticeRepository noticeRepository;
	private final PoolAddRequestService poolAddRequestService;
	private final AdminActionLogService actionLogService;
	private final StringRedisTemplate redisTemplate;
	private final String notificationQueueKey;
	private final int notificationBatchSize;
	private final long notificationDelayMs;
	private final long notificationStaleSendingTimeoutMs;
	private final int eventSchedulerPoolSize;
	private final long eventSchedulerDelayMs;

	public AdminDashboardService(
			AppUserRepository userRepository,
			PoolRepository poolRepository,
			SubscriptionRepository subscriptionRepository,
			RegistrationEventRepository eventRepository,
			NotificationRepository notificationRepository,
			UserDeviceRepository userDeviceRepository,
			PoolNoticeSourceRepository noticeSourceRepository,
			PoolNoticeRepository noticeRepository,
			PoolAddRequestService poolAddRequestService,
			AdminActionLogService actionLogService,
			StringRedisTemplate redisTemplate,
			@Value("${swimpulse.notification.queue-key:swimpulse:notifications}") String notificationQueueKey,
			@Value("${swimpulse.notification.worker-batch-size:20}") int notificationBatchSize,
			@Value("${swimpulse.notification.worker-delay-ms:1000}") long notificationDelayMs,
			@Value("${swimpulse.notification.stale-sending-timeout-ms:120000}") long notificationStaleSendingTimeoutMs,
			@Value("${swimpulse.event.scheduler-pool-size:1}") int eventSchedulerPoolSize,
			@Value("${swimpulse.event.scheduler-delay-ms:30000}") long eventSchedulerDelayMs
	) {
		this.userRepository = userRepository;
		this.poolRepository = poolRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.eventRepository = eventRepository;
		this.notificationRepository = notificationRepository;
		this.userDeviceRepository = userDeviceRepository;
		this.noticeSourceRepository = noticeSourceRepository;
		this.noticeRepository = noticeRepository;
		this.poolAddRequestService = poolAddRequestService;
		this.actionLogService = actionLogService;
		this.redisTemplate = redisTemplate;
		this.notificationQueueKey = notificationQueueKey;
		this.notificationBatchSize = notificationBatchSize;
		this.notificationDelayMs = notificationDelayMs;
		this.notificationStaleSendingTimeoutMs = notificationStaleSendingTimeoutMs;
		this.eventSchedulerPoolSize = eventSchedulerPoolSize;
		this.eventSchedulerDelayMs = eventSchedulerDelayMs;
	}

	@Transactional(readOnly = true)
	public AdminDashboardResponse getDashboard() {
		return new AdminDashboardResponse(
				Instant.now(),
				overview(),
				notificationDashboard(),
				noticeDashboard(),
				workerDashboard(),
				deliveryStats(),
				topSubscribedPools(),
				topSubscribedDistricts(),
				pendingPoolAddRequests(),
				poolAddRequests(),
				failedNotifications(),
				recentActionLogs()
		);
	}

	@Transactional(readOnly = true)
	public AdminOperationsDashboardResponse getOperationsDashboard() {
		return new AdminOperationsDashboardResponse(
				Instant.now(),
				notificationDashboard(),
				deliveryStats(),
				workerDashboard(),
				failedNotifications(),
				recentActionLogs()
		);
	}

	@Transactional(readOnly = true)
	public AdminServiceDashboardResponse getServiceDashboard() {
		return new AdminServiceDashboardResponse(
				Instant.now(),
				overview(),
				noticeDashboard(),
				topSubscribedPools(),
				topSubscribedDistricts(),
				pendingPoolAddRequests(),
				poolAddRequests()
		);
	}

	private AdminDashboardResponse.AdminOverview overview() {
		return new AdminDashboardResponse.AdminOverview(
				userRepository.count(),
				poolRepository.count(),
				subscriptionRepository.count(),
				countEvents(),
				userDeviceRepository.countByEnabledTrue()
		);
	}

	private long countEvents() {
		return Arrays.stream(EventStatus.values())
				.mapToLong(eventRepository::countByStatus)
				.sum();
	}

	private AdminDashboardResponse.AdminNotificationDashboard notificationDashboard() {
		return new AdminDashboardResponse.AdminNotificationDashboard(
				notificationQueueLength(),
				notificationRepository.count(),
				notificationRepository.countStaleByStatus(
						NotificationStatus.SENDING,
						Instant.now().minusMillis(notificationStaleSendingTimeoutMs)
				),
				Arrays.stream(NotificationStatus.values())
						.map(status -> new AdminMetricCount(status.name(), notificationRepository.countByStatus(status)))
						.toList()
		);
	}

	private AdminDashboardResponse.AdminNotificationDeliveryStats deliveryStats() {
		long queued = notificationRepository.countByStatus(NotificationStatus.QUEUED);
		long sending = notificationRepository.countByStatus(NotificationStatus.SENDING);
		long sent = notificationRepository.countByStatus(NotificationStatus.SENT);
		long failed = notificationRepository.countByStatus(NotificationStatus.FAILED);
		long completed = sent + failed;
		return new AdminDashboardResponse.AdminNotificationDeliveryStats(
				queued,
				sending,
				sent,
				failed,
				completed == 0 ? 0 : (double) sent / completed,
				completed == 0 ? 0 : (double) failed / completed
		);
	}

	private long notificationQueueLength() {
		Long size = redisTemplate.opsForList().size(notificationQueueKey);
		return size == null ? 0 : size;
	}

	private AdminDashboardResponse.AdminNoticeDashboard noticeDashboard() {
		return new AdminDashboardResponse.AdminNoticeDashboard(
				noticeRepository.count(),
				noticeRepository.countByPeriodsMigratedAtIsNull(),
				noticeRepository.countByPeriodsMigrationErrorIsNotNull(),
				Arrays.stream(NoticeSourceStatus.values())
						.map(status -> new AdminMetricCount(status.name(), noticeSourceRepository.countByStatus(status)))
						.toList(),
				Arrays.stream(NoticeExtractionStatus.values())
						.map(status -> new AdminMetricCount(status.name(), noticeRepository.countByExtractionStatus(status)))
						.toList(),
				Arrays.stream(NoticeOcrStatus.values())
						.map(status -> new AdminMetricCount(status.name(), noticeRepository.countByOcrStatus(status)))
						.toList()
		);
	}

	private AdminDashboardResponse.AdminWorkerDashboard workerDashboard() {
		return new AdminDashboardResponse.AdminWorkerDashboard(
				notificationBatchSize,
				notificationDelayMs,
				notificationStaleSendingTimeoutMs,
				eventSchedulerPoolSize,
				eventSchedulerDelayMs
		);
	}

	private List<AdminPoolRankingResponse> topSubscribedPools() {
		return subscriptionRepository.findPoolSubscriptionRankings(PageRequest.of(0, 10))
				.stream()
				.map(AdminPoolRankingResponse::from)
				.toList();
	}

	private List<AdminDistrictRankingResponse> topSubscribedDistricts() {
		return subscriptionRepository.findDistrictSubscriptionRankings(PageRequest.of(0, 10))
				.stream()
				.map(AdminDistrictRankingResponse::from)
				.toList();
	}

	private List<PoolAddRequestResponse> pendingPoolAddRequests() {
		return poolAddRequestService.findPending(10);
	}

	private List<PoolAddRequestResponse> poolAddRequests() {
		return poolAddRequestService.findRecent(10);
	}

	private List<NotificationResponse> failedNotifications() {
		return notificationRepository.findByStatusOrderByCreatedAtDesc(NotificationStatus.FAILED, PageRequest.of(0, 10))
				.stream()
				.map(NotificationResponse::from)
				.toList();
	}

	private List<AdminActionLogResponse> recentActionLogs() {
		return actionLogService.findRecent(null, null, 10);
	}
}
