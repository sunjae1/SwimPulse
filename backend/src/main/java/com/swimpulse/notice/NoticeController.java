package com.swimpulse.notice;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pools/{poolId}/notices")
public class NoticeController {
	private final NoticeCrawlerService noticeCrawlerService;

	public NoticeController(NoticeCrawlerService noticeCrawlerService) {
		this.noticeCrawlerService = noticeCrawlerService;
	}

	@PostMapping("/scan")
	public NoticeScanResponse scan(@PathVariable Long poolId) {
		return noticeCrawlerService.scan(poolId);
	}
}
