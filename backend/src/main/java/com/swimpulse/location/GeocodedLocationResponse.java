package com.swimpulse.location;

public record GeocodedLocationResponse(
		String address,
		double latitude,
		double longitude
) {
}
