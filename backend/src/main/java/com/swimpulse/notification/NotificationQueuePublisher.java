package com.swimpulse.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class NotificationQueuePublisher {
	private static final Logger log = LoggerFactory.getLogger(NotificationQueuePublisher.class);

	private final StringRedisTemplate redisTemplate;
	private final String queueKey;

	public NotificationQueuePublisher(
			StringRedisTemplate redisTemplate,
			@Value("${swimpulse.notification.queue-key:swimpulse:notifications}") String queueKey
	) {
		this.redisTemplate = redisTemplate;
		this.queueKey = queueKey;
	}

	public void publish(Long notificationId) {
		redisTemplate.opsForList().rightPush(queueKey, notificationId.toString());
		log.info("Notification queued. notificationId={} queueKey={}", notificationId, queueKey);
	}

	public void publishAfterCommit(Long notificationId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			publish(notificationId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				publish(notificationId);
			}
		});
	}
}
