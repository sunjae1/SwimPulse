package com.swimpulse.auth;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.user.AppUser;
import com.swimpulse.user.UserResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MobileGoogleAuthService {
	private static final String GOOGLE_ISSUER = "https://accounts.google.com";
	private static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

	private final OAuthLoginService oauthLoginService;
	private final JwtService jwtService;
	private final JwtDecoder googleJwtDecoder;

	public MobileGoogleAuthService(
			OAuthLoginService oauthLoginService,
			JwtService jwtService,
			@Value("${spring.security.oauth2.client.registration.google.client-id}") String googleClientId
	) {
		this.oauthLoginService = oauthLoginService;
		this.jwtService = jwtService;
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWK_SET_URI).build();
		decoder.setJwtValidator(googleValidator(googleClientId));
		this.googleJwtDecoder = decoder;
	}

	public MobileLoginResponse login(MobileGoogleLoginRequest request) {
		Jwt googleJwt = decode(request.idToken());
		String providerUserId = required(googleJwt, "sub");
		String email = required(googleJwt, "email");
		String displayName = optional(googleJwt, "name", email.substring(0, email.indexOf("@")));
		String profileImageUrl = optional(googleJwt, "picture", null);
		AppUser user = oauthLoginService.loginGoogleUser(providerUserId, email, displayName, profileImageUrl);
		return new MobileLoginResponse(jwtService.createToken(user), UserResponse.from(user));
	}

	private Jwt decode(String idToken) {
		try {
			return googleJwtDecoder.decode(idToken);
		} catch (JwtException exception) {
			throw new BadRequestException("Invalid Google id token.");
		}
	}

	private OAuth2TokenValidator<Jwt> googleValidator(String googleClientId) {
		OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(GOOGLE_ISSUER);
		OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud", audiences ->
				audiences != null && audiences.contains(googleClientId));
		OAuth2TokenValidator<Jwt> emailVerified = jwt -> {
			Boolean verified = jwt.getClaim("email_verified");
			if (Boolean.TRUE.equals(verified)) {
				return OAuth2TokenValidatorResult.success();
			}
			return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Google email is not verified.", null));
		};
		return new DelegatingOAuth2TokenValidator<>(issuer, audience, emailVerified);
	}

	private String required(Jwt jwt, String claim) {
		String value = jwt.getClaimAsString(claim);
		if (!StringUtils.hasText(value)) {
			throw new BadRequestException("Google id token is missing claim: " + claim);
		}
		return value;
	}

	private String optional(Jwt jwt, String claim, String fallback) {
		String value = jwt.getClaimAsString(claim);
		return StringUtils.hasText(value) ? value : fallback;
	}
}
