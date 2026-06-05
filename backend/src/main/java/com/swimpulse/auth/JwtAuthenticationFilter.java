package com.swimpulse.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
	private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

	private final JwtService jwtService;

	public JwtAuthenticationFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		findAccessToken(request).ifPresent(token -> {
			jwtService.parse(token).ifPresentOrElse(user -> {
				MDC.put("userId", user.id().toString());
				UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
						user,
						null,
						List.of()
				);
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}, () -> log.debug("JWT cookie rejected. method={} uri={}", request.getMethod(), request.getRequestURI()));
		});
		filterChain.doFilter(request, response);
	}

	private java.util.Optional<String> findAccessToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return java.util.Optional.empty();
		}
		for (Cookie cookie : cookies) {
			if (AuthCookieService.ACCESS_TOKEN_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
				return java.util.Optional.of(cookie.getValue());
			}
		}
		return java.util.Optional.empty();
	}
}
