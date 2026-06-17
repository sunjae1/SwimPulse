package com.swimpulse.location;

public record LocationSearchCandidate(
		String title,
		String category,
		String address,
		String roadAddress,
		String link,
		Double latitude,
		Double longitude,
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
		return new LocationSearchCandidate(title, category, address, roadAddress, link, null, null, null, link);
	}

	public LocationSearchCandidate withEnrichment(
			Double latitude,
			Double longitude,
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
				distanceMeters,
				link
		);
	}
}
