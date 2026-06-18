package com.swimpulse.notice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class NoticeOcrQueuePublisher {
	private static final Logger log = LoggerFactory.getLogger(NoticeOcrQueuePublisher.class);

	private final StringRedisTemplate redisTemplate;
	private final String queueKey;

	public NoticeOcrQueuePublisher(
			StringRedisTemplate redisTemplate,
			@Value("${swimpulse.notice.ocr.queue-key:swimpulse:notice-ocr}") String queueKey
	) {
		this.redisTemplate = redisTemplate;
		this.queueKey = queueKey;
	}

	public void publishAfterCommit(Long noticeId) {
		if (noticeId == null) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			publish(noticeId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				publish(noticeId);
			}
		});
	}

	private void publish(Long noticeId) {
		redisTemplate.opsForList().rightPush(queueKey, noticeId.toString());
		log.info("Notice OCR queued. noticeId={} queueKey={}", noticeId, queueKey);
	}
}
