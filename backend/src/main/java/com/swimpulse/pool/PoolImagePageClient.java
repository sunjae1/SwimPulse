package com.swimpulse.pool;

import org.jsoup.nodes.Document;

public interface PoolImagePageClient {
	Document fetch(String url);

	ImageProbe probe(String imageUrl);

	record ImageProbe(boolean image, String contentType, long contentLength) {
		public static ImageProbe image(String contentType, long contentLength) {
			return new ImageProbe(true, contentType, contentLength);
		}

		public static ImageProbe notImage(String contentType, long contentLength) {
			return new ImageProbe(false, contentType, contentLength);
		}
	}
}
