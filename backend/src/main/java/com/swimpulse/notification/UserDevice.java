package com.swimpulse.notification;

import com.swimpulse.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
		name = "user_devices",
		uniqueConstraints = @UniqueConstraint(name = "uk_user_device", columnNames = {"user_id", "device_id"})
)
public class UserDevice {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@Column(name = "device_id", nullable = false, length = 80)
	private String deviceId;

	@Column(nullable = false, length = 500)
	private String fcmToken;

	@Column(nullable = false)
	private boolean enabled;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant lastSeenAt;

	protected UserDevice() {
	}

	public UserDevice(AppUser user, String deviceId, String fcmToken) {
		this.user = user;
		this.deviceId = deviceId;
		this.fcmToken = fcmToken;
		this.enabled = true;
		this.createdAt = Instant.now();
		this.lastSeenAt = Instant.now();
	}

	public void updateToken(String fcmToken) {
		this.fcmToken = fcmToken;
		this.enabled = true;
		this.lastSeenAt = Instant.now();
	}

	public void disable() {
		this.enabled = false;
		this.lastSeenAt = Instant.now();
	}

	public String getFcmToken() {
		return fcmToken;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public Instant getLastSeenAt() {
		return lastSeenAt;
	}
}
