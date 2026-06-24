package com.swimpulse.notification;

import java.util.List;
import org.springframework.data.domain.Page;

public record NotificationPageResponse(
		List<NotificationResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean first,
		boolean last,
		long unreadElements
) {
	public static NotificationPageResponse from(Page<Notification> page, long unreadElements) {
		return new NotificationPageResponse(
				page.getContent().stream()
						.map(NotificationResponse::from)
						.toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages(),
				page.isFirst(),
				page.isLast(),
				unreadElements
		);
	}
}
