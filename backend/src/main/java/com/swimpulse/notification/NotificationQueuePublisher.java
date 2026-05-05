package com.swimpulse.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationQueuePublisher {
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
	}
}
