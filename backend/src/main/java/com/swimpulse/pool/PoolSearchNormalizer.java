package com.swimpulse.pool;

import java.util.Locale;

public final class PoolSearchNormalizer {
	private PoolSearchNormalizer() {
	}

	public static String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.replaceAll("<[^>]*>", "")
				.replaceAll("\\s+", "")
				.replace("수영장", "")
				.toLowerCase(Locale.ROOT);
	}
}
