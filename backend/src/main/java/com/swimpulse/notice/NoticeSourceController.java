package com.swimpulse.notice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pools/notice-sources")
public class NoticeSourceController {
	private static final Logger log = LoggerFactory.getLogger(NoticeSourceController.class);

	private final NoticeCrawlerService noticeCrawlerService;

	public NoticeSourceController(NoticeCrawlerService noticeCrawlerService) {
		this.noticeCrawlerService = noticeCrawlerService;
	}

	@PostMapping("/reverify")
	public NoticeSourceReverificationResponse reverify(@RequestParam(required = false) Integer limit) {
		log.info("Notice source reverification requested. limit={}", limit);
		return noticeCrawlerService.reverifySources(limit);
	}
}
