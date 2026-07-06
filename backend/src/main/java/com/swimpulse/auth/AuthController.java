package com.swimpulse.auth;

import com.swimpulse.user.UserResponse;
import com.swimpulse.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
	private static final Logger log = LoggerFactory.getLogger(AuthController.class);

	private final UserService userService;
	private final AuthCookieService authCookieService;
	private final MobileGoogleAuthService mobileGoogleAuthService;

	public AuthController(
			UserService userService,
			AuthCookieService authCookieService,
			MobileGoogleAuthService mobileGoogleAuthService
	) {
		this.userService = userService;
		this.authCookieService = authCookieService;
		this.mobileGoogleAuthService = mobileGoogleAuthService;
	}

	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
		return userService.findUser(user.id());
	}

	@PostMapping("/auth/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		if (request.getSession(false) != null) {
			request.getSession(false).invalidate();
		}
		authCookieService.clearAccessToken(response);
		authCookieService.clearSession(response);
		log.info("User logged out.");
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/auth/mobile/google")
	public MobileLoginResponse mobileGoogleLogin(@Valid @org.springframework.web.bind.annotation.RequestBody MobileGoogleLoginRequest request) {
		return mobileGoogleAuthService.login(request);
	}
}
