package com.swimpulse.pool;

import java.time.Instant;

public record PoolAddRequestResponse(
		Long id,
		Long requestedByUserId,
		String requestedByEmail,
		String title,
		String category,
		String address,
		String roadAddress,
		String homepageUrl,
		Double latitude,
		Double longitude,
		PoolAddRequestStatus status,
		Long approvedPoolId,
		String approvedPoolName,
		String adminNote,
		Instant createdAt,
		Instant reviewedAt,
		Long reviewedByAdminId
) {
	public static PoolAddRequestResponse from(PoolAddRequest request) {
		Pool approvedPool = request.getApprovedPool();
		return new PoolAddRequestResponse(
				request.getId(),
				request.getRequestedBy().getId(),
				request.getRequestedBy().getEmail(),
				request.getTitle(),
				request.getCategory(),
				request.getAddress(),
				request.getRoadAddress(),
				request.getHomepageUrl(),
				request.getLatitude(),
				request.getLongitude(),
				request.getStatus(),
				approvedPool == null ? null : approvedPool.getId(),
				approvedPool == null ? null : approvedPool.getName(),
				request.getAdminNote(),
				request.getCreatedAt(),
				request.getReviewedAt(),
				request.getReviewedBy() == null ? null : request.getReviewedBy().getId()
		);
	}
}
