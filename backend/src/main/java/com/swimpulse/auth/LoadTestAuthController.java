package com.swimpulse.auth;

import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/loadtest/auth")
@ConditionalOnProperty(name = "swimpulse.loadtest.enabled", havingValue = "true")
public class LoadTestAuthController {
	private static final int MAX_TOKEN_COUNT = 100;

	private final AppUserRepository userRepository;
	private final JwtService jwtService;

	public LoadTestAuthController(AppUserRepository userRepository, JwtService jwtService) {
		this.userRepository = userRepository;
		this.jwtService = jwtService;
	}

	@PostMapping("/tokens")
	@Transactional
	public LoadTestTokenResponse issueTokens(@RequestParam(defaultValue = "10") int count) {
		int tokenCount = Math.max(1, Math.min(count, MAX_TOKEN_COUNT));
		List<LoadTestToken> tokens = new ArrayList<>(tokenCount);
		for (int i = 1; i <= tokenCount; i++) {
			int userNumber = i;
			String email = "loadtest-user-" + userNumber + "@swimpulse.local";
			AppUser user = userRepository.findByEmail(email)
					.orElseGet(() -> userRepository.save(new AppUser(email, "Load Test User " + userNumber, null)));
			tokens.add(new LoadTestToken(user.getId(), user.getEmail(), jwtService.createToken(user)));
		}
		return new LoadTestTokenResponse(tokens.size(), tokens);
	}

	public record LoadTestTokenResponse(int count, List<LoadTestToken> tokens) {
	}

	public record LoadTestToken(Long userId, String email, String token) {
	}
}
