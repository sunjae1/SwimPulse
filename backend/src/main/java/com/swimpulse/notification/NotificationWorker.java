package com.swimpulse.notification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationWorker {
	private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

	private final StringRedisTemplate redisTemplate;
	private final NotificationService notificationService;
	private final String queueKey;
	private final int batchSize;
	private final Duration staleSendingTimeout;

	public NotificationWorker(
			StringRedisTemplate redisTemplate,
			NotificationService notificationService,
			MeterRegistry meterRegistry,
			@Value("${swimpulse.notification.queue-key:swimpulse:notifications}") String queueKey,
			@Value("${swimpulse.notification.worker-batch-size:20}") int batchSize,
			@Value("${swimpulse.notification.stale-sending-timeout-ms:120000}") long staleSendingTimeoutMs
	) {
		this.redisTemplate = redisTemplate;
		this.notificationService = notificationService;
		this.queueKey = queueKey;
		this.batchSize = batchSize;
		this.staleSendingTimeout = Duration.ofMillis(staleSendingTimeoutMs);
		Gauge.builder("swimpulse.notification.queue.length", redisTemplate, template -> {
					Long size = template.opsForList().size(queueKey);
					return size == null ? 0 : size;
				})
				.description("Current notification Redis queue length")
				.tag("queue", queueKey)
				.register(meterRegistry);
	}

	@Scheduled(fixedDelayString = "${swimpulse.notification.worker-delay-ms:1000}", scheduler = "notificationTaskScheduler")
	public void process() {
		int requeued = notificationService.requeueStaleSending(staleSendingTimeout);
		if (requeued > 0) {
			log.warn("Stale notification recovery completed. requeued={}", requeued);
		}
		for (int i = 0; i < batchSize; i++) {
			String rawId = redisTemplate.opsForList().leftPop(queueKey);
			if (rawId == null) {
				return;
			}
			try {
				Long notificationId = Long.valueOf(rawId);
				log.info("Notification queue item received. notificationId={}", notificationId);
				boolean shouldRetry = notificationService.deliver(notificationId);
				if (shouldRetry) {
					notificationService.requeue(notificationId);
				}
			} catch (RuntimeException exception) {
				log.warn("Failed to process notification queue item: {}", rawId, exception);
			}
		}
	}
}
