package com.swimpulse.notice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pools/notices/periods")
public class NoticePeriodMigrationController {
	private static final Logger log = LoggerFactory.getLogger(NoticePeriodMigrationController.class);

	private final NoticeRegistrationPeriodService periodService;

	public NoticePeriodMigrationController(NoticeRegistrationPeriodService periodService) {
		this.periodService = periodService;
	}

	@PostMapping("/migrate")
	public NoticePeriodMigrationResponse migrate(@RequestParam(required = false) Integer limit) {
		log.info("Legacy notice period migration requested. limit={}", limit);
		return periodService.migrateLegacyPeriods(limit);
	}

	@GetMapping("/migration-status")
	public NoticePeriodMigrationStatus migrationStatus() {
		return periodService.migrationStatus();
	}
}
