package com.swimpulse.admin;

import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminActionLogService {
	private static final int DEFAULT_LIMIT = 20;

	private final AdminActionLogRepository logRepository;
	private final AppUserRepository userRepository;

	public AdminActionLogService(AdminActionLogRepository logRepository, AppUserRepository userRepository) {
		this.logRepository = logRepository;
		this.userRepository = userRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void success(Long adminUserId, String actionType, String targetType, Long targetId, String message) {
		save(adminUserId, actionType, targetType, targetId, AdminActionResultStatus.SUCCESS, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failure(Long adminUserId, String actionType, String targetType, Long targetId, String message) {
		save(adminUserId, actionType, targetType, targetId, AdminActionResultStatus.FAILED, message);
	}

	@Transactional(readOnly = true)
	public List<AdminActionLogResponse> findRecent(String actionType, AdminActionResultStatus resultStatus, Integer limit) {
		return logRepository.searchRecent(
						normalizeBlank(actionType),
						resultStatus,
						PageRequest.of(0, normalizeLimit(limit))
				)
				.stream()
				.map(AdminActionLogResponse::from)
				.toList();
	}

	private void save(
			Long adminUserId,
			String actionType,
			String targetType,
			Long targetId,
			AdminActionResultStatus resultStatus,
			String message
	) {
		AppUser admin = adminUserId == null
				? null
				: userRepository.findById(adminUserId).orElse(null);
		logRepository.save(new AdminActionLog(admin, actionType, targetType, targetId, resultStatus, message));
	}

	private int normalizeLimit(Integer limit) {
		if (limit == null) {
			return DEFAULT_LIMIT;
		}
		return Math.max(1, Math.min(limit, 100));
	}

	private String normalizeBlank(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
