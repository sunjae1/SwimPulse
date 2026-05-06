package com.swimpulse.location;

public record LocationSearchCandidate(
		String title,
		String category,
		String address,
		String roadAddress,
		String link,
		Double latitude,
		Double longitude,
		Boolean alreadyExists,
		Long matchedPoolId,
		Double distanceMeters,
		String homepageUrl
) {
	public static LocationSearchCandidate basic(
			String title,
			String category,
			String address,
			String roadAddress,
			String link
	) {
		return new LocationSearchCandidate(title, category, address, roadAddress, link, null, null, false, null, null, link);
	}

	public LocationSearchCandidate withEnrichment(
			Double latitude,
			Double longitude,
			boolean alreadyExists,
			Long matchedPoolId,
			Double distanceMeters
	) {
		return new LocationSearchCandidate(
				title,
				category,
				address,
				roadAddress,
				link,
				latitude,
				longitude,
				alreadyExists,
				matchedPoolId,
				distanceMeters,
				link
		);
	}
}
