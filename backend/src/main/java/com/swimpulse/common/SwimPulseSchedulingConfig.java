package com.swimpulse.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SwimPulseSchedulingConfig {
	@Bean(name = "notificationTaskScheduler")
	public ThreadPoolTaskScheduler notificationTaskScheduler(
			@Value("${swimpulse.notification.scheduler-pool-size:1}") int poolSize
	) {
		return taskScheduler("notification-worker-", poolSize);
	}

	@Bean(name = "noticeOcrTaskScheduler")
	public ThreadPoolTaskScheduler noticeOcrTaskScheduler(
			@Value("${swimpulse.notice.ocr.scheduler-pool-size:1}") int poolSize
	) {
		return taskScheduler("notice-ocr-worker-", poolSize);
	}

	@Bean(name = "eventTaskScheduler")
	public ThreadPoolTaskScheduler eventTaskScheduler(
			@Value("${swimpulse.event.scheduler-pool-size:1}") int poolSize
	) {
		return taskScheduler("event-scheduler-", poolSize);
	}

	private ThreadPoolTaskScheduler taskScheduler(String threadNamePrefix, int poolSize) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(Math.max(1, poolSize));
		scheduler.setThreadNamePrefix(threadNamePrefix);
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(10);
		return scheduler;
	}
}
