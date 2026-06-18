package com.swimpulse.notification;

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

	public NotificationWorker(
			StringRedisTemplate redisTemplate,
			NotificationService notificationService,
			@Value("${swimpulse.notification.queue-key:swimpulse:notifications}") String queueKey,
			@Value("${swimpulse.notification.worker-batch-size:20}") int batchSize
	) {
		this.redisTemplate = redisTemplate;
		this.notificationService = notificationService;
		this.queueKey = queueKey;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${swimpulse.notification.worker-delay-ms:1000}", scheduler = "notificationTaskScheduler")
	public void process() {
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
