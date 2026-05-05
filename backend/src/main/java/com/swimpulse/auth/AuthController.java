package com.swimpulse.auth;

import com.swimpulse.user.UserResponse;
import com.swimpulse.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
	private final UserService userService;
	private final AuthCookieService authCookieService;

	public AuthController(UserService userService, AuthCookieService authCookieService) {
		this.userService = userService;
		this.authCookieService = authCookieService;
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
		return ResponseEntity.noContent().build();
	}
}
