package com.swimpulse.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 120)
	private String email;

	@Column(nullable = false, length = 80)
	private String displayName;

	@Column(length = 500)
	private String profileImageUrl;

	@Column(nullable = false)
	private boolean notificationEnabled;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant lastLoginAt;

	protected AppUser() {
	}

	public AppUser(String email, String displayName, String profileImageUrl) {
		this.email = email;
		this.displayName = displayName;
		this.profileImageUrl = profileImageUrl;
		this.notificationEnabled = true;
		this.createdAt = Instant.now();
		this.lastLoginAt = Instant.now();
	}

	public void updateOAuthProfile(String email, String displayName, String profileImageUrl) {
		this.email = email;
		this.displayName = displayName;
		this.profileImageUrl = profileImageUrl;
		this.lastLoginAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getProfileImageUrl() {
		return profileImageUrl;
	}

	public boolean isNotificationEnabled() {
		return notificationEnabled;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}
}
