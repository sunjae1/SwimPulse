package com.swimpulse.notice;

public enum NoticeSourceStatus {
	// Discovered but not yet proven to be a reusable notice entry point.
	CANDIDATE,
	// Successfully fetched and recognized as a notice board or registration guide.
	VERIFIED,
	// Successfully fetched but unrelated to notices or registration periods.
	INACTIVE,
	// Repeated access failures reached the configured threshold.
	FAILED
}
