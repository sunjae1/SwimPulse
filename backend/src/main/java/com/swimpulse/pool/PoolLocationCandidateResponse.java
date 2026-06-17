package com.swimpulse.pool;

import com.swimpulse.location.LocationSearchCandidate;

public record PoolLocationCandidateResponse(
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
	public static PoolLocationCandidateResponse of(
			LocationSearchCandidate candidate,
			Double latitude,
			Double longitude,
			Pool exactMatch,
			Double distanceMeters
	) {
		return new PoolLocationCandidateResponse(
				candidate.title(),
				candidate.category(),
				candidate.address(),
				candidate.roadAddress(),
				candidate.link(),
				latitude,
				longitude,
				exactMatch != null,
				exactMatch == null ? null : exactMatch.getId(),
				distanceMeters,
				candidate.link()
		);
	}
}
