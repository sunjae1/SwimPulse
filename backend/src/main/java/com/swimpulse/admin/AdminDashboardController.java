package com.swimpulse.admin;

import com.swimpulse.auth.AuthenticatedUser;
import com.swimpulse.notice.NoticeScanResponse;
import com.swimpulse.notification.NotificationResponse;
import com.swimpulse.notification.NotificationService;
import com.swimpulse.pool.HomepageEnrichmentResult;
import com.swimpulse.pool.PoolAddRequestResponse;
import com.swimpulse.pool.PoolAddRequestService;
import com.swimpulse.pool.PoolImageEnrichmentResult;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {
	private final AdminDashboardService adminDashboardService;
	private final AdminActionLogService actionLogService;
	private final NotificationService notificationService;
	private final PoolAddRequestService poolAddRequestService;
	private final Duration staleSendingTimeout;

	public AdminDashboardController(
			AdminDashboardService adminDashboardService,
			AdminActionLogService actionLogService,
			NotificationService notificationService,
			PoolAddRequestService poolAddRequestService,
			@Value("${swimpulse.notification.stale-sending-timeout-ms:120000}") long staleSendingTimeoutMs
	) {
		this.adminDashboardService = adminDashboardService;
		this.actionLogService = actionLogService;
		this.notificationService = notificationService;
		this.poolAddRequestService = poolAddRequestService;
		this.staleSendingTimeout = Duration.ofMillis(staleSendingTimeoutMs);
	}

	@GetMapping("/dashboard")
	public AdminDashboardResponse dashboard() {
		return adminDashboardService.getDashboard();
	}

	@GetMapping("/dashboard/operations")
	public AdminOperationsDashboardResponse operationsDashboard() {
		return adminDashboardService.getOperationsDashboard();
	}

	@GetMapping("/dashboard/service")
	public AdminServiceDashboardResponse serviceDashboard() {
		return adminDashboardService.getServiceDashboard();
	}

	@GetMapping("/action-logs")
	public List<AdminActionLogResponse> actionLogs(
			@RequestParam(required = false) String actionType,
			@RequestParam(required = false) AdminActionResultStatus resultStatus,
			@RequestParam(defaultValue = "20") int limit
	) {
		return actionLogService.findRecent(actionType, resultStatus, limit);
	}

	@PostMapping("/notifications/{notificationId}/requeue")
	public NotificationResponse requeueFailedNotification(
			@AuthenticationPrincipal AuthenticatedUser admin,
			@PathVariable Long notificationId
	) {
		return audited(
				admin,
				"REQUEUE_FAILED_NOTIFICATION",
				"NOTIFICATION",
				notificationId,
				() -> notificationService.requeueFailed(notificationId)
		);
	}

	@PostMapping("/notifications/requeue-stale")
	public AdminActionResponse requeueStaleNotifications(
			@AuthenticationPrincipal AuthenticatedUser admin,
			@RequestParam(defaultValue = "50") int limit
	) {
		return audited(
				admin,
				"REQUEUE_STALE_NOTIFICATIONS",
				"NOTIFICATION",
				null,
				() -> {
					int affected = notificationService.requeueStaleSending(staleSendingTimeout, limit);
					return new AdminActionResponse(
							"requeue-stale-notifications",
							affected,
							affected + " stale SENDING notification(s) requeued."
					);
				}
		);
	}

	@PostMapping("/pool-add-requests/{requestId}/approve")
	public PoolAddRequestResponse approvePoolAddRequest(
			@AuthenticationPrincipal AuthenticatedUser admin,
			@PathVariable Long requestId
	) {
		return audited(
				admin,
				"APPROVE_POOL_ADD_REQUEST",
				"POOL_ADD_REQUEST",
				requestId,
				() -> poolAddRequestService.approve(requestId, admin.id())
		);
	}

	@PostMapping("/pool-add-requests/{requestId}/reject")
	public PoolAddRequestResponse rejectPoolAddRequest(
			@AuthenticationPrincipal AuthenticatedUser admin,
			@PathVariable Long requestId,
			@Valid @RequestBody(required = false) AdminRejectPoolAddRequest request
	) {
		return audited(
				admin,
				"REJECT_POOL_ADD_REQUEST",
				"POOL_ADD_REQUEST",
				requestId,
				() -> poolAddRequestService.reject(requestId, admin.id(), request == null ? null : request.reason())
		);
	}

	@PostMapping("/pool-add-requests/{requestId}/postprocess")
	public AdminPoolPostprocessResponse postprocessPoolAddRequest(
			@AuthenticationPrincipal AuthenticatedUser admin,
			@PathVariable Long requestId
	) {
		return audited(
				admin,
				"POSTPROCESS_POOL_ADD_REQUEST",
				"POOL_ADD_REQUEST",
				requestId,
				() -> poolAddRequestService.postprocess(requestId)
		);
	}

	@PostMapping("/pool-add-requests/{requestId}/postprocess/homepage")
	public HomepageEnrichmentResult postprocessPoolAddRequestHomepage(
			@AuthenticationPrincipal AuthenticatedUser admin,
			@PathVariable Long requestId
	) {
		return audited(
				admin,
				"POSTPROCESS_POOL_ADD_REQUEST_HOMEPAGE",
				"POOL_ADD_REQUEST",
				requestId,
				() -> poolAddRequestService.postprocessHomepage(requestId)
		);
	}

	@PostMapping("/pool-add-requests/{requestId}/postprocess/image")
	public PoolImageEnrichmentResult postprocessPoolAddRequestImage(
			@AuthenticationPrincipal AuthenticatedUser admin,
			@PathVariable Long requestId
	) {
		return audited(
				admin,
				"POSTPROCESS_POOL_ADD_REQUEST_IMAGE",
				"POOL_ADD_REQUEST",
				requestId,
				() -> poolAddRequestService.postprocessImage(requestId)
		);
	}

	@PostMapping("/pool-add-requests/{requestId}/postprocess/notices")
	public NoticeScanResponse postprocessPoolAddRequestNotices(
			@AuthenticationPrincipal AuthenticatedUser admin,
			@PathVariable Long requestId
	) {
		return audited(
				admin,
				"POSTPROCESS_POOL_ADD_REQUEST_NOTICES",
				"POOL_ADD_REQUEST",
				requestId,
				() -> poolAddRequestService.postprocessNotices(requestId)
		);
	}

	private <T> T audited(
			AuthenticatedUser admin,
			String actionType,
			String targetType,
			Long targetId,
			Supplier<T> action
	) {
		try {
			T result = action.get();
			actionLogService.success(admin == null ? null : admin.id(), actionType, targetType, targetId, "Action completed.");
			return result;
		} catch (RuntimeException exception) {
			actionLogService.failure(admin == null ? null : admin.id(), actionType, targetType, targetId, exception.getMessage());
			throw exception;
		}
	}
}
