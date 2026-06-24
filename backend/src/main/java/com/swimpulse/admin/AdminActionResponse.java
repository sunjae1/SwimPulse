package com.swimpulse.admin;

public record AdminActionResponse(
		String action,
		int affected,
		String message
) {
}
