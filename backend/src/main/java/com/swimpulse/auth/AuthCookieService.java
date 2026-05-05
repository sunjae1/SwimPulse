package com.swimpulse.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthCookieService {
	public static final String ACCESS_TOKEN_COOKIE = "swimpulse_access_token";
	private static final String SESSION_COOKIE = "JSESSIONID";

	private final boolean secure;
	private final Duration maxAge;

	public AuthCookieService(
			@Value("${swimpulse.auth.cookie-secure:false}") boolean secure,
			@Value("${swimpulse.auth.jwt-expiration-hours:168}") long expirationHours
	) {
		this.secure = secure;
		this.maxAge = Duration.ofHours(expirationHours);
	}

	public void addAccessToken(HttpServletResponse response, String token) {
		response.addHeader(HttpHeaders.SET_COOKIE, baseCookie(token)
				.maxAge(maxAge)
				.build()
				.toString());
	}

	public void clearAccessToken(HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, baseCookie("")
				.maxAge(Duration.ZERO)
				.build()
				.toString());
	}

	public void clearSession(HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION_COOKIE, "")
				.httpOnly(true)
				.secure(secure)
				.sameSite("Lax")
				.path("/")
				.maxAge(Duration.ZERO)
				.build()
				.toString());
	}

	private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
		return ResponseCookie.from(ACCESS_TOKEN_COOKIE, value)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Lax")
				.path("/");
	}
}
