package com.swimpulse.admin;

import java.time.Instant;

public record AdminActionLogResponse(
		Long id,
		Long adminUserId,
		String adminEmail,
		String actionType,
		String targetType,
		Long targetId,
		AdminActionResultStatus resultStatus,
		String message,
		Instant createdAt
) {
	public static AdminActionLogResponse from(AdminActionLog log) {
		return new AdminActionLogResponse(
				log.getId(),
				log.getAdminUser() == null ? null : log.getAdminUser().getId(),
				log.getAdminUser() == null ? null : log.getAdminUser().getEmail(),
				log.getActionType(),
				log.getTargetType(),
				log.getTargetId(),
				log.getResultStatus(),
				log.getMessage(),
				log.getCreatedAt()
		);
	}
}
