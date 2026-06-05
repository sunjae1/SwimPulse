package com.swimpulse.pool;

/**
 * Source that supplied the current homepage URL or the latest homepage candidate.
 */
public enum HomepageSource {
	/** The value came from Naver Local Search API local.json results. */
	NAVER_LOCAL_SEARCH,

	/** The value was selected by a user from a location-search candidate. */
	USER_LOCATION_CANDIDATE,

	/** The value was entered or corrected manually by an operator. */
	MANUAL,

	/** The value came from imported public facility data. */
	PUBLIC_DATA,

	/** The value predates source tracking or its origin is unknown. */
	UNKNOWN
}
