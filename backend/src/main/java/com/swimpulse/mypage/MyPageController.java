package com.swimpulse.mypage;

import com.swimpulse.auth.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/my-page")
public class MyPageController {
	private static final Logger log = LoggerFactory.getLogger(MyPageController.class);

	private final MyPageService myPageService;

	public MyPageController(MyPageService myPageService) {
		this.myPageService = myPageService;
	}

	@GetMapping
	public MyPageResponse findMyPage(@AuthenticationPrincipal AuthenticatedUser user) {
		log.info("My page requested. userId={}", user.id());
		return myPageService.findMyPage(user.id());
	}
}
