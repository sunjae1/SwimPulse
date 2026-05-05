package com.swimpulse.pool;

public record PoolResponse(
		Long id,
		String name,
		String address,
		String district,
		String websiteUrl,
		String description
) {
	public static PoolResponse from(Pool pool) {
		return new PoolResponse(
				pool.getId(),
				pool.getName(),
				pool.getAddress(),
				pool.getDistrict(),
				pool.getWebsiteUrl(),
				pool.getDescription()
		);
	}
}
