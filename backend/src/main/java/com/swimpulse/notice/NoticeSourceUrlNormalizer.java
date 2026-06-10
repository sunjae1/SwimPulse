package com.swimpulse.notice;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

final class NoticeSourceUrlNormalizer {
	private static final Pattern SESSION_ID = Pattern.compile(";jsessionid=[^/?#;]*", Pattern.CASE_INSENSITIVE);

	private NoticeSourceUrlNormalizer() {
	}

	static String normalize(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		int fragmentIndex = normalized.indexOf('#');
		if (fragmentIndex >= 0) {
			normalized = normalized.substring(0, fragmentIndex);
		}
		normalized = SESSION_ID.matcher(normalized).replaceAll("");
		if (normalized.isBlank()) {
			return normalized;
		}
		try {
			URI uri = URI.create(normalized);
			if (uri.getHost() == null) {
				return normalized;
			}
			String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			int port = uri.getPort();
			if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
				port = -1;
			}
			String path = uri.getPath();
			if (path == null || path.isBlank()) {
				path = "/";
			}
			return new URI(
					scheme,
					uri.getUserInfo(),
					host,
					port,
					path,
					uri.getQuery(),
					null
			).normalize().toASCIIString();
		} catch (IllegalArgumentException | URISyntaxException exception) {
			return normalized;
		}
	}
}
