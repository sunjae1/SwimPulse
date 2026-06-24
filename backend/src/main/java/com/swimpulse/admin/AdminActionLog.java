package com.swimpulse.admin;

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
import java.time.Instant;

@Entity
@Table(name = "admin_action_logs")
public class AdminActionLog {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "admin_user_id")
	private AppUser adminUser;

	@Column(nullable = false, length = 80)
	private String actionType;

	@Column(nullable = false, length = 80)
	private String targetType;

	private Long targetId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AdminActionResultStatus resultStatus;

	@Column(length = 1000)
	private String message;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected AdminActionLog() {
	}

	public AdminActionLog(
			AppUser adminUser,
			String actionType,
			String targetType,
			Long targetId,
			AdminActionResultStatus resultStatus,
			String message
	) {
		this.adminUser = adminUser;
		this.actionType = truncate(actionType, 80);
		this.targetType = truncate(targetType, 80);
		this.targetId = targetId;
		this.resultStatus = resultStatus;
		this.message = truncate(message, 1000);
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public AppUser getAdminUser() {
		return adminUser;
	}

	public String getActionType() {
		return actionType;
	}

	public String getTargetType() {
		return targetType;
	}

	public Long getTargetId() {
		return targetId;
	}

	public AdminActionResultStatus getResultStatus() {
		return resultStatus;
	}

	public String getMessage() {
		return message;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	private String truncate(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}
}
