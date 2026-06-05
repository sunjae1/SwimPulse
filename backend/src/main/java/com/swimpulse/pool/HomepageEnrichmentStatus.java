package com.swimpulse.pool;

public enum HomepageEnrichmentStatus {
	/** Empty homepageUrl was filled from a confident candidate. */
	UPDATED,

	/** Existing homepageUrl was replaced because the old URL looked like a broad institution root. */
	AUTO_UPDATED,

	/** Existing homepageUrl already matches the best confident candidate. */
	VERIFIED,

	/** Existing homepageUrl was kept because no better confident candidate was found. */
	UNCHANGED,

	/** Pool was intentionally skipped before external lookup. */
	SKIPPED,

	/** A candidate exists, but the service should not choose automatically. */
	NEEDS_REVIEW,

	/** External lookup or verification failed. */
	FAILED
}
