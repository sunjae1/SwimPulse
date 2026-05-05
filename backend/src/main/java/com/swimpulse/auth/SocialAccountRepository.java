package com.swimpulse.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
	Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);
}
