package com.swimpulse.auth;

import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class OAuthLoginService {
	private final AppUserRepository userRepository;
	private final SocialAccountRepository socialAccountRepository;

	public OAuthLoginService(AppUserRepository userRepository, SocialAccountRepository socialAccountRepository) {
		this.userRepository = userRepository;
		this.socialAccountRepository = socialAccountRepository;
	}

	@Transactional
	public AppUser loginGoogleUser(OAuth2User principal) {
		String providerUserId = required(principal, "sub");
		String email = required(principal, "email");
		String displayName = optional(principal, "name", email.substring(0, email.indexOf("@")));
		String profileImageUrl = optional(principal, "picture", null);

		return socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, providerUserId)
				.map(account -> {
					AppUser user = account.getUser();
					user.updateOAuthProfile(email, displayName, profileImageUrl);
					account.updateProfile(email, displayName, profileImageUrl);
					return user;
				})
				.orElseGet(() -> {
					AppUser user = userRepository.findByEmail(email)
							.orElseGet(() -> userRepository.save(new AppUser(email, displayName, profileImageUrl)));
					user.updateOAuthProfile(email, displayName, profileImageUrl);
					socialAccountRepository.save(new SocialAccount(
							user,
							SocialProvider.GOOGLE,
							providerUserId,
							email,
							displayName,
							profileImageUrl
					));
					return user;
				});
	}

	private String required(OAuth2User principal, String attribute) {
		String value = principal.getAttribute(attribute);
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException("Google OAuth response is missing attribute: " + attribute);
		}
		return value;
	}

	private String optional(OAuth2User principal, String attribute, String fallback) {
		String value = principal.getAttribute(attribute);
		return StringUtils.hasText(value) ? value : fallback;
	}
}
