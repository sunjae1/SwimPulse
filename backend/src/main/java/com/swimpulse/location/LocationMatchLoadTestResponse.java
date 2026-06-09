package com.swimpulse.location;

public record LocationMatchLoadTestResponse(
		String strategy,
		int candidateCount,
		int matchedCount,
		long elapsedMicros
) {
}
