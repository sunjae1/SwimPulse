package com.swimpulse.auth;

import com.swimpulse.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
		name = "social_accounts",
		uniqueConstraints = @UniqueConstraint(name = "uk_social_account_provider_user", columnNames = {"provider", "provider_user_id"})
)
public class SocialAccount {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SocialProvider provider;

	@Column(name = "provider_user_id", nullable = false, length = 120)
	private String providerUserId;

	@Column(nullable = false, length = 120)
	private String email;

	@Column(nullable = false, length = 80)
	private String displayName;

	@Column(length = 500)
	private String profileImageUrl;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	protected SocialAccount() {
	}

	public SocialAccount(
			AppUser user,
			SocialProvider provider,
			String providerUserId,
			String email,
			String displayName,
			String profileImageUrl
	) {
		this.user = user;
		this.provider = provider;
		this.providerUserId = providerUserId;
		this.email = email;
		this.displayName = displayName;
		this.profileImageUrl = profileImageUrl;
		this.createdAt = Instant.now();
		this.updatedAt = Instant.now();
	}

	public void updateProfile(String email, String displayName, String profileImageUrl) {
		this.email = email;
		this.displayName = displayName;
		this.profileImageUrl = profileImageUrl;
		this.updatedAt = Instant.now();
	}

	public AppUser getUser() {
		return user;
	}
}
