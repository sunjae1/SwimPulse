package com.swimpulse.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swimpulse.user.AppUser;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class JwtAuthenticationFilterTests {
	private final JwtService jwtService = new JwtService("01234567890123456789012345678901", 1);
	private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void authenticatesBearerJwt() throws ServletException, IOException {
		String token = jwtService.createToken(user(7L));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
		request.addHeader("Authorization", "Bearer " + token);

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		AuthenticatedUser principal = (AuthenticatedUser) SecurityContextHolder.getContext()
				.getAuthentication()
				.getPrincipal();
		assertEquals(7L, principal.id());
		assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
	}

	@Test
	void keepsCookieJwtAuthenticationFlow() throws ServletException, IOException {
		String token = jwtService.createToken(user(8L));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
		request.setCookies(new jakarta.servlet.http.Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE, token));

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		AuthenticatedUser principal = (AuthenticatedUser) SecurityContextHolder.getContext()
				.getAuthentication()
				.getPrincipal();
		assertEquals(8L, principal.id());
	}

	@Test
	void invalidBearerTokenDoesNotAuthenticate() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
		request.addHeader("Authorization", "Bearer invalid-token");

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}

	private AppUser user(Long id) {
		AppUser user = new AppUser("mobile" + id + "@example.com", "Mobile " + id, null);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}
}
