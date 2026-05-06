package com.swimpulse.pool;

import java.util.List;

public record GeocodeBatchResponse(
		int processed,
		int success,
		int failed,
		List<GeocodePoolResult> results
) {
	public static GeocodeBatchResponse from(List<GeocodePoolResult> results) {
		int success = (int) results.stream()
				.filter(result -> result.status() == GeocodeStatus.SUCCESS)
				.count();
		int failed = (int) results.stream()
				.filter(result -> result.status() == GeocodeStatus.FAILED)
				.count();
		return new GeocodeBatchResponse(results.size(), success, failed, results);
	}
}
