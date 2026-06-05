package com.swimpulse.notice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pools/{poolId}/notices")
public class NoticeController {
	private static final Logger log = LoggerFactory.getLogger(NoticeController.class);

	private final NoticeCrawlerService noticeCrawlerService;

	public NoticeController(NoticeCrawlerService noticeCrawlerService) {
		this.noticeCrawlerService = noticeCrawlerService;
	}

	@PostMapping("/scan")
	public NoticeScanResponse scan(@PathVariable Long poolId) {
		log.info("Notice scan requested. poolId={}", poolId);
		return noticeCrawlerService.scan(poolId);
	}
}
