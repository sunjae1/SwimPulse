package com.swimpulse.pool;

import java.math.BigDecimal;
import java.time.Instant;

public record PoolResponse(
		Long id,
		String name,
		String district,
		String description,
		Integer completionYear,
		String indoorOutdoorTypeName,
		String ownerAgencyName,
		String managementAgencyName,
		String operatingOrganizationName,
		String contactNumber,
		BigDecimal standardPoolLengthMeters,
		Integer standardPoolLaneCount,
		String postalCode,
		String lotNumberAddress,
		String roadNameAddress,
		String homepageUrl,
		HomepageSource homepageSource,
		HomepageVerificationStatus homepageStatus,
		Instant homepageVerifiedAt,
		String homepageCandidateTitle,
		String homepageCandidateAddress,
		String homepageCandidateLink,
		String imageUrl,
		Double latitude,
		Double longitude,
		GeocodeStatus geocodeStatus
) {
	public static PoolResponse from(Pool pool) {
		return new PoolResponse(
				pool.getId(),
				pool.getName(),
				pool.getDistrict(),
				pool.getDescription(),
				pool.getCompletionYear(),
				pool.getIndoorOutdoorTypeName(),
				pool.getOwnerAgencyName(),
				pool.getManagementAgencyName(),
				pool.getOperatingOrganizationName(),
				pool.getContactNumber(),
				pool.getStandardPoolLengthMeters(),
				pool.getStandardPoolLaneCount(),
				pool.getPostalCode(),
				pool.getLotNumberAddress(),
				pool.getRoadNameAddress(),
				pool.getHomepageUrl(),
				pool.getHomepageSource(),
				pool.getHomepageStatus(),
				pool.getHomepageVerifiedAt(),
				pool.getHomepageCandidateTitle(),
				pool.getHomepageCandidateAddress(),
				pool.getHomepageCandidateLink(),
				pool.getImageUrl(),
				pool.getLatitude(),
				pool.getLongitude(),
				pool.getGeocodeStatus()
		);
	}
}
