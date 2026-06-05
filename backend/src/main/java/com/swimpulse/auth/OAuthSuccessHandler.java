package com.swimpulse.auth;

import com.swimpulse.user.AppUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class OAuthSuccessHandler implements org.springframework.security.web.authentication.AuthenticationSuccessHandler {
	private static final Logger log = LoggerFactory.getLogger(OAuthSuccessHandler.class);

	private final OAuthLoginService oauthLoginService;
	private final JwtService jwtService;
	private final AuthCookieService authCookieService;
	private final String successRedirectUri;

	public OAuthSuccessHandler(
			OAuthLoginService oauthLoginService,
			JwtService jwtService,
			AuthCookieService authCookieService,
			@Value("${swimpulse.auth.success-redirect-uri:http://localhost:3000}") String successRedirectUri
	) {
		this.oauthLoginService = oauthLoginService;
		this.jwtService = jwtService;
		this.authCookieService = authCookieService;
		this.successRedirectUri = successRedirectUri;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
			throws IOException, ServletException {
		if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}

		AppUser user = oauthLoginService.loginGoogleUser(oauthToken.getPrincipal());
		if (request.getSession(false) != null) {
			request.getSession(false).invalidate();
		}
		authCookieService.addAccessToken(response, jwtService.createToken(user));
		authCookieService.clearSession(response);
		log.info("OAuth login succeeded. provider={} userId={}", oauthToken.getAuthorizedClientRegistrationId(), user.getId());
		response.sendRedirect(successRedirectUri);
	}
}
