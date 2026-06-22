package com.swimpulse.pool;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PoolImageEnrichmentService {
	private static final Logger log = LoggerFactory.getLogger(PoolImageEnrichmentService.class);
	private static final String DEFAULT_POOL_IMAGE_URL = "/swimpulse-pool-shark.png";
	private static final List<String> EXCLUDED_IMAGE_SIGNALS = List.of(
			"logo",
			"favicon",
			"icon",
			"sprite",
			"avatar",
			"barcode",
			"spinner",
			"blank",
			"loading",
			"banner",
			"/ban_",
			"ban_",
			"_banner",
			"popup",
			"quick",
			"btn_",
			"/btn",
			"youtube.com",
			"youtu.be",
			"ytimg.com",
			"img.youtube.com",
			"facebook.com",
			"instagram.com",
			"kakao",
			"naverblog",
			"blog",
			"qrcode",
			"qr-code"
	);
	private static final List<String> PREFERRED_IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");

	private final PoolImagePageClient pageClient;

	public PoolImageEnrichmentService(PoolImagePageClient pageClient) {
		this.pageClient = pageClient;
	}

	@Transactional
	public PoolImageEnrichmentResult enrich(Pool pool) {
		return enrich(pool, true);
	}

	@Transactional
	public PoolImageEnrichmentResult enrichFallback(Pool pool) {
		return enrich(pool, false);
	}

	private PoolImageEnrichmentResult enrich(Pool pool, boolean includeRepresentativeImages) {
		String previousImageUrl = pool.getImageUrl();
		if (!hasText(pool.getHomepageUrl())) {
			return useDefaultImage(pool, previousImageUrl, "Pool homepageUrl is empty.");
		}
		try {
			Document document = pageClient.fetch(pool.getHomepageUrl());
			if (includeRepresentativeImages) {
				PoolImageEnrichmentResult representative = tryCandidates(
						pool,
						previousImageUrl,
						imageCandidates(document),
						true,
						"Representative image found from homepage metadata."
				);
				if (representative != null) {
					return representative;
				}
			}
			PoolImageEnrichmentResult favicon = tryCandidates(
					pool,
					previousImageUrl,
					faviconCandidates(document),
					false,
					"Favicon found from official homepage."
			);
			return favicon != null
					? favicon
					: useDefaultImage(pool, previousImageUrl, "Homepage did not contain a usable og:image or favicon.");
		} catch (RuntimeException exception) {
			log.warn("Pool image enrichment failed. poolId={} homepageUrl={} message={}",
					pool.getId(), pool.getHomepageUrl(), exception.getMessage());
			return useDefaultImage(pool, previousImageUrl, "Homepage image enrichment failed: " + exception.getMessage());
		}
	}

	private PoolImageEnrichmentResult tryCandidates(
			Pool pool,
			String previousImageUrl,
			List<ImageCandidate> candidates,
			boolean representativeImage,
			String successReason
	) {
		for (ImageCandidate candidate : candidates) {
			if (!isUsableCandidate(candidate.url(), representativeImage)) {
				log.debug("Pool image candidate skipped. poolId={} url={} source={} reason=weak-signal",
						pool.getId(), candidate.url(), candidate.source());
				continue;
			}
			PoolImagePageClient.ImageProbe probe = pageClient.probe(candidate.url());
			if (!probe.image() || (representativeImage && isTinyImage(probe))) {
				log.debug("Pool image candidate rejected. poolId={} url={} source={} contentType={} contentLength={}",
						pool.getId(), candidate.url(), candidate.source(), probe.contentType(), probe.contentLength());
				continue;
			}
			pool.updateImageUrl(candidate.url());
			PoolImageEnrichmentStatus status = sameUrl(previousImageUrl, candidate.url())
					? PoolImageEnrichmentStatus.UNCHANGED
					: PoolImageEnrichmentStatus.UPDATED;
			log.info("Pool image enriched. poolId={} status={} source={} imageUrl={}",
					pool.getId(), status, candidate.source(), candidate.url());
			return result(pool, status, candidate.url(), previousImageUrl, candidate.source(), successReason);
		}
		return null;
	}

	private PoolImageEnrichmentResult useDefaultImage(Pool pool, String previousImageUrl, String reason) {
		pool.updateImageUrl(DEFAULT_POOL_IMAGE_URL);
		PoolImageEnrichmentStatus status = sameUrl(previousImageUrl, DEFAULT_POOL_IMAGE_URL)
				? PoolImageEnrichmentStatus.UNCHANGED
				: PoolImageEnrichmentStatus.UPDATED;
		log.info("Pool default image assigned. poolId={} status={} imageUrl={} reason={}",
				pool.getId(), status, DEFAULT_POOL_IMAGE_URL, reason);
		return result(pool, status, DEFAULT_POOL_IMAGE_URL, previousImageUrl, "default:shark-logo", reason);
	}

	List<ImageCandidate> imageCandidates(Document document) {
		Set<ImageCandidate> candidates = new LinkedHashSet<>();
		addMetaCandidate(document, candidates, "meta[property=og:image:secure_url]", "og:image:secure_url");
		addMetaCandidate(document, candidates, "meta[property=og:image]", "og:image");
		addMetaCandidate(document, candidates, "meta[name=twitter:image]", "twitter:image");
		addMetaCandidate(document, candidates, "meta[name=twitter:image:src]", "twitter:image:src");
		addMetaCandidate(document, candidates, "meta[itemprop=image]", "itemprop:image");
		for (Element link : document.select("link[rel=image_src][href]")) {
			addCandidate(candidates, document, link.attr("href"), "link:image_src");
		}
		return candidates.stream()
				.sorted((left, right) -> Integer.compare(score(right), score(left)))
				.toList();
	}

	List<ImageCandidate> faviconCandidates(Document document) {
		Set<ImageCandidate> candidates = new LinkedHashSet<>();
		for (Element link : document.select("link[href]")) {
			String rel = link.attr("rel").toLowerCase(Locale.ROOT);
			if (rel.contains("icon") || rel.contains("apple-touch-icon") || rel.contains("mask-icon")) {
				addCandidate(candidates, document, link.attr("href"), "favicon:" + rel);
			}
		}
		addCandidate(candidates, document, "/apple-touch-icon.png", "favicon:apple-touch-icon-default");
		addCandidate(candidates, document, "/favicon.ico", "favicon:default");
		return candidates.stream()
				.sorted((left, right) -> Integer.compare(score(right), score(left)))
				.toList();
	}

	private void addMetaCandidate(Document document, Set<ImageCandidate> candidates, String selector, String source) {
		for (Element element : document.select(selector)) {
			addCandidate(candidates, document, element.attr("content"), source);
		}
	}

	private void addCandidate(Set<ImageCandidate> candidates, Document document, String rawUrl, String source) {
		if (!hasText(rawUrl)) {
			return;
		}
		candidates.add(new ImageCandidate(normalizeImageUrl(resolveUrl(document.baseUri(), rawUrl)), source));
	}

	private String resolveUrl(String baseUrl, String rawUrl) {
		String trimmed = rawUrl.trim();
		if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || !hasText(baseUrl)) {
			return trimmed;
		}
		try {
			return URI.create(baseUrl).resolve(trimmed).toString();
		} catch (IllegalArgumentException exception) {
			return trimmed;
		}
	}

	private boolean isUsableCandidate(String imageUrl, boolean representativeImage) {
		if (!hasText(imageUrl)) {
			return false;
		}
		String normalized = imageUrl.toLowerCase(Locale.ROOT);
		if (normalized.startsWith("data:")) {
			return false;
		}
		if (representativeImage && normalized.endsWith(".svg")) {
			return false;
		}
		if (representativeImage && EXCLUDED_IMAGE_SIGNALS.stream().anyMatch(normalized::contains)) {
			return false;
		}
		return normalized.startsWith("http://") || normalized.startsWith("https://");
	}

	private int score(ImageCandidate candidate) {
		int score = switch (candidate.source()) {
			case "og:image:secure_url" -> 100;
			case "og:image" -> 95;
			case "twitter:image", "twitter:image:src" -> 80;
			case "itemprop:image" -> 70;
			case "link:image_src" -> 65;
			case "favicon:apple-touch-icon", "favicon:apple-touch-icon-precomposed" -> 60;
			case "favicon:icon", "favicon:shortcut icon" -> 55;
			case "favicon:apple-touch-icon-default" -> 35;
			case "favicon:default" -> 30;
			default -> 20;
		};
		String lower = candidate.url().toLowerCase(Locale.ROOT);
		if (PREFERRED_IMAGE_EXTENSIONS.stream().anyMatch(lower::contains)) {
			score += 10;
		}
		if (lower.contains("visual") || lower.contains("main")) {
			score += 5;
		}
		return score;
	}

	private boolean isTinyImage(PoolImagePageClient.ImageProbe probe) {
		return probe.contentLength() > -1 && probe.contentLength() < 1024;
	}

	private boolean sameUrl(String left, String right) {
		if (!hasText(left) || !hasText(right)) {
			return false;
		}
		return normalizeImageUrl(left).equals(normalizeImageUrl(right));
	}

	private String normalizeImageUrl(String imageUrl) {
		String trimmed = imageUrl == null ? "" : imageUrl.trim();
		try {
			URI uri = new URI(trimmed);
			String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			String path = uri.getRawPath() == null ? "" : uri.getRawPath();
			String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
			int port = uri.getPort();
			String portPart = port < 0 ? "" : ":" + port;
			return scheme + "://" + host + portPart + path + query;
		} catch (URISyntaxException exception) {
			return trimmed;
		}
	}

	private PoolImageEnrichmentResult result(
			Pool pool,
			PoolImageEnrichmentStatus status,
			String imageUrl,
			String previousImageUrl,
			String source,
			String reason
	) {
		return new PoolImageEnrichmentResult(
				pool.getId(),
				pool.getName(),
				status,
				imageUrl,
				previousImageUrl,
				source,
				reason
		);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	record ImageCandidate(String url, String source) {
	}
}
