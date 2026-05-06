package com.swimpulse.pool;

import java.math.BigDecimal;

public record PoolResponse(
		Long id,
		String name,
		String address,
		String district,
		String websiteUrl,
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
		String imageUrl,
		Double latitude,
		Double longitude,
		GeocodeStatus geocodeStatus
) {
	public static PoolResponse from(Pool pool) {
		return new PoolResponse(
				pool.getId(),
				pool.getName(),
				pool.getAddress(),
				pool.getDistrict(),
				pool.getWebsiteUrl(),
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
				pool.getImageUrl(),
				pool.getLatitude(),
				pool.getLongitude(),
				pool.getGeocodeStatus()
		);
	}
}
