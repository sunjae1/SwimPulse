package com.swimpulse.admin;

import com.swimpulse.pool.PoolResponse;

public record AdminPoolHomepageCorrectionResponse(
		PoolResponse pool,
		String previousName,
		String previousHomepageUrl,
		int previousHomepageRevision,
		int homepageRevision,
		int inactivatedSources,
		int reviewRequiredEvents,
		int reviewRequiredSubscriptions,
		int cancelledNotifications,
		int queuedReviewNotifications
) {
}
