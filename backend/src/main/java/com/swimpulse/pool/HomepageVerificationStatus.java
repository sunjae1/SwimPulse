package com.swimpulse.pool;

/**
 * Verification state of a pool homepage URL.
 */
public enum HomepageVerificationStatus {
	/** No homepage verification has been attempted since source tracking was added. */
	UNVERIFIED,

	/** The current homepage matches a confident Naver Local Search candidate. */
	VERIFIED,

	/** The current homepage was automatically replaced by a more reliable candidate. */
	AUTO_UPDATED,

	/** A candidate was found, but automatic replacement was intentionally skipped. */
	NEEDS_REVIEW,

	/** Verification could not find a usable candidate or the external request failed. */
	FAILED
}
