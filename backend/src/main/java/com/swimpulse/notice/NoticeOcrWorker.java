package com.swimpulse.notice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NoticeOcrWorker {
	private static final Logger log = LoggerFactory.getLogger(NoticeOcrWorker.class);

	private final StringRedisTemplate redisTemplate;
	private final NoticeCrawlerService noticeCrawlerService;
	private final String queueKey;
	private final int batchSize;

	public NoticeOcrWorker(
			StringRedisTemplate redisTemplate,
			NoticeCrawlerService noticeCrawlerService,
			@Value("${swimpulse.notice.ocr.queue-key:swimpulse:notice-ocr}") String queueKey,
			@Value("${swimpulse.notice.ocr.worker-batch-size:3}") int batchSize
	) {
		this.redisTemplate = redisTemplate;
		this.noticeCrawlerService = noticeCrawlerService;
		this.queueKey = queueKey;
		this.batchSize = Math.max(1, batchSize);
	}

	@Scheduled(fixedDelayString = "${swimpulse.notice.ocr.worker-delay-ms:2000}", scheduler = "noticeOcrTaskScheduler")
	public void process() {
		for (int index = 0; index < batchSize; index++) {
			String rawId = redisTemplate.opsForList().leftPop(queueKey);
			if (rawId == null) {
				return;
			}
			try {
				Long noticeId = Long.valueOf(rawId);
				log.info("Notice OCR queue item received. noticeId={}", noticeId);
				noticeCrawlerService.enrichNoticeWithOcr(noticeId);
			} catch (RuntimeException exception) {
				log.warn("Failed to process notice OCR queue item: {}", rawId, exception);
			}
		}
	}
}
