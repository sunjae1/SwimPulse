package com.swimpulse.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OAuthFailureHandler implements AuthenticationFailureHandler {
	private static final Logger log = LoggerFactory.getLogger(OAuthFailureHandler.class);

	private final String failureRedirectUri;

	public OAuthFailureHandler(
			@Value("${swimpulse.auth.failure-redirect-uri:${swimpulse.auth.success-redirect-uri:http://localhost:3000}}")
			String failureRedirectUri
	) {
		this.failureRedirectUri = failureRedirectUri;
	}

	@Override
	public void onAuthenticationFailure(
			HttpServletRequest request,
			HttpServletResponse response,
			AuthenticationException exception
	) throws IOException, ServletException {
		log.warn("OAuth login failed. uri={} errorType={} message={}",
				request.getRequestURI(),
				exception.getClass().getSimpleName(),
				exception.getMessage());
		String redirectUri = UriComponentsBuilder.fromUriString(failureRedirectUri)
				.replaceQueryParam("login", "error")
				.build()
				.toUriString();
		response.sendRedirect(redirectUri);
	}
}
