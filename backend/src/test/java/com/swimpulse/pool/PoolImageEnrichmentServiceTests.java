package com.swimpulse.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class PoolImageEnrichmentServiceTests {
	@Test
	void enrichStoresOgImageFromHomepage() {
		FakePoolImagePageClient client = new FakePoolImagePageClient("""
				<html>
				  <head><meta property="og:image" content="/images/pool-main.jpg"></head>
				</html>
				""", Map.of(
				"https://center.example.com/images/pool-main.jpg",
				PoolImagePageClient.ImageProbe.image("image/jpeg", 20_000)
		));
		PoolImageEnrichmentService service = new PoolImageEnrichmentService(client);
		Pool pool = new Pool("테스트 수영장", "테스트구", "테스트 시설");
		pool.updateHomepageUrl("https://center.example.com/main");

		PoolImageEnrichmentResult result = service.enrich(pool);

		assertEquals(PoolImageEnrichmentStatus.UPDATED, result.status());
		assertEquals("https://center.example.com/images/pool-main.jpg", result.imageUrl());
		assertEquals("https://center.example.com/images/pool-main.jpg", pool.getImageUrl());
		assertEquals("og:image", result.source());
	}

	@Test
	void enrichSkipsLogoSvgAndUsesTwitterImage() {
		FakePoolImagePageClient client = new FakePoolImagePageClient("""
				<html>
				  <head>
				    <meta property="og:image" content="/images/logo.svg">
				    <meta name="twitter:image" content="/assets/pool-visual.webp">
				  </head>
				</html>
				""", Map.of(
				"https://center.example.com/assets/pool-visual.webp",
				PoolImagePageClient.ImageProbe.image("image/webp", 30_000)
		));
		PoolImageEnrichmentService service = new PoolImageEnrichmentService(client);
		Pool pool = new Pool("테스트 수영장", "테스트구", "테스트 시설");
		pool.updateHomepageUrl("https://center.example.com/main");

		PoolImageEnrichmentResult result = service.enrich(pool);

		assertEquals(PoolImageEnrichmentStatus.UPDATED, result.status());
		assertEquals("https://center.example.com/assets/pool-visual.webp", result.imageUrl());
		assertEquals(List.of("https://center.example.com/assets/pool-visual.webp"), client.probedUrls);
	}

	@Test
	void enrichUsesFaviconWhenRepresentativeCandidatesAreNotImages() {
		FakePoolImagePageClient client = new FakePoolImagePageClient("""
				<html>
				  <head>
				    <meta property="og:image" content="/notice">
				    <link rel="icon" href="/favicon.ico">
				  </head>
				</html>
				""", Map.of(
				"https://center.example.com/notice",
				PoolImagePageClient.ImageProbe.notImage("text/html", 10_000),
				"https://center.example.com/favicon.ico",
				PoolImagePageClient.ImageProbe.image("image/x-icon", 512)
		));
		PoolImageEnrichmentService service = new PoolImageEnrichmentService(client);
		Pool pool = new Pool("테스트 수영장", "테스트구", "테스트 시설");
		pool.updateHomepageUrl("https://center.example.com/main");

		PoolImageEnrichmentResult result = service.enrich(pool);

		assertEquals(PoolImageEnrichmentStatus.UPDATED, result.status());
		assertEquals("https://center.example.com/favicon.ico", result.imageUrl());
		assertEquals("favicon:icon", result.source());
	}

	@Test
	void enrichUsesDefaultImageWhenFaviconIsMissing() {
		FakePoolImagePageClient client = new FakePoolImagePageClient("""
				<html>
				  <head><meta property="og:image" content="/notice"></head>
				</html>
				""", Map.of(
				"https://center.example.com/notice",
				PoolImagePageClient.ImageProbe.notImage("text/html", 10_000)
		));
		PoolImageEnrichmentService service = new PoolImageEnrichmentService(client);
		Pool pool = new Pool("테스트 수영장", "테스트구", "테스트 시설");
		pool.updateHomepageUrl("https://center.example.com/main");

		PoolImageEnrichmentResult result = service.enrich(pool);

		assertEquals(PoolImageEnrichmentStatus.UPDATED, result.status());
		assertEquals("/swimpulse-pool-shark.png", result.imageUrl());
		assertEquals("default:shark-logo", result.source());
		assertEquals("/swimpulse-pool-shark.png", pool.getImageUrl());
	}

	@Test
	void enrichUsesDefaultImageWithoutHomepage() {
		FakePoolImagePageClient client = new FakePoolImagePageClient("<html></html>", Map.of());
		PoolImageEnrichmentService service = new PoolImageEnrichmentService(client);
		Pool pool = new Pool("홈페이지 없는 수영장", "테스트구", "테스트 시설");

		PoolImageEnrichmentResult result = service.enrich(pool);

		assertEquals(PoolImageEnrichmentStatus.UPDATED, result.status());
		assertEquals("/swimpulse-pool-shark.png", result.imageUrl());
		assertEquals(0, client.fetchCount);
	}

	private static class FakePoolImagePageClient implements PoolImagePageClient {
		private final String html;
		private final Map<String, ImageProbe> probes;
		private final List<String> probedUrls = new ArrayList<>();
		private int fetchCount;

		private FakePoolImagePageClient(String html, Map<String, ImageProbe> probes) {
			this.html = html;
			this.probes = probes;
		}

		@Override
		public Document fetch(String url) {
			fetchCount++;
			return Jsoup.parse(html, url);
		}

		@Override
		public ImageProbe probe(String imageUrl) {
			probedUrls.add(imageUrl);
			return probes.getOrDefault(imageUrl, ImageProbe.notImage("text/html", -1));
		}
	}
}
