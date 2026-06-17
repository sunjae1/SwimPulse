package com.swimpulse.location;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.RedisJsonCacheService;
import com.swimpulse.common.TooManyRequestsException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class NaverLocalSearchClient {
	private static final Logger log = LoggerFactory.getLogger(NaverLocalSearchClient.class);
	private static final String NAVER_LOCAL_SEARCH_URL = "https://openapi.naver.com/v1/search/local.json";
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");

	private final RestClient restClient;
	private final String clientId;
	private final String clientSecret;
	private final RedisJsonCacheService redisCache;
	private final Duration locationSearchTtl;
	private final Duration poolLocationCandidateTtl;

	public NaverLocalSearchClient(
			RestClient.Builder restClientBuilder,
			@Value("${swimpulse.naver.search.client-id:}") String clientId,
			@Value("${swimpulse.naver.search.client-secret:}") String clientSecret,
			RedisJsonCacheService redisCache,
			@Value("${swimpulse.cache.location-search-ttl:PT5M}") Duration locationSearchTtl,
			@Value("${swimpulse.cache.pool-location-candidates-ttl:PT1H}") Duration poolLocationCandidateTtl
	) {
		this.restClient = restClientBuilder.build();
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.redisCache = redisCache;
		this.locationSearchTtl = locationSearchTtl;
		this.poolLocationCandidateTtl = poolLocationCandidateTtl;
	}

	public List<LocationSearchCandidate> search(String query, int display) {
		return searchWithCache(
				"location-search",
				"swimpulse:cache:location-search:v1:",
				query,
				display,
				locationSearchTtl
		);
	}

	public List<LocationSearchCandidate> searchPoolLocationCandidates(String query, int display) {
		return searchWithCache(
				"pool-location-candidates",
				"swimpulse:cache:pool-location-candidates:v1:",
				query,
				display,
				poolLocationCandidateTtl
		);
	}

	private List<LocationSearchCandidate> searchWithCache(
			String cacheName,
			String keyPrefix,
			String query,
			int display,
			Duration ttl
	) {
		if (!isConfigured()) {
			throw new BadRequestException("Naver Search API credentials are not configured.");
		}
		String cacheKey = keyPrefix + redisCache.hash(normalizeCacheInput(query) + "|display=" + display);
		return redisCache.getList(cacheName, cacheKey, LocationSearchCandidate.class)
				.orElseGet(() -> {
					List<LocationSearchCandidate> candidates = requestNaverLocalSearch(query, display);
					redisCache.put(cacheName, cacheKey, candidates, ttl);
					return candidates;
				});
	}

	private List<LocationSearchCandidate> requestNaverLocalSearch(String query, int display) {
		log.info("Naver local search requested. query={} display={}", query, display);

		URI uri = UriComponentsBuilder.fromUriString(NAVER_LOCAL_SEARCH_URL)
				.queryParam("query", query)
				.queryParam("display", display)
				.queryParam("start", 1)
				.queryParam("sort", "random")
				.encode()
				.build()
				.toUri();

		NaverLocalSearchResponse response;
		try {
			response = restClient.get()
					.uri(uri)
					.header("X-Naver-Client-Id", clientId)
					.header("X-Naver-Client-Secret", clientSecret)
					.retrieve()
					.body(NaverLocalSearchResponse.class);
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 429) {
				throw new TooManyRequestsException("Naver local search request failed: 429 Too Many Requests");
			}
			throw new BadRequestException("Naver local search request failed: "
					+ exception.getStatusCode().value() + " " + exception.getStatusText());
		}

		if (response == null || response.items() == null) {
			log.info("Naver local search returned no items. query={}", query);
			return List.of();
		}

		List<LocationSearchCandidate> candidates = response.items()
				.stream()
				.map(item -> LocationSearchCandidate.basic(
						stripHtml(item.title()),
						stripHtml(item.category()),
						emptyToNull(item.address()),
						emptyToNull(item.roadAddress()),
						emptyToNull(item.link())
				))
				.toList();
		log.info("Naver local search completed. query={} resultCount={}", query, candidates.size());
		return candidates;
	}

	private boolean isConfigured() {
		return hasText(clientId) && hasText(clientSecret);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private String stripHtml(String value) {
		if (value == null) {
			return null;
		}
		return HTML_TAG.matcher(value).replaceAll("").trim();
	}

	private String emptyToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value;
	}

	private String normalizeCacheInput(String value) {
		return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
	}

	public record NaverLocalSearchResponse(List<NaverLocalSearchItem> items) {
	}

	public record NaverLocalSearchItem(
			String title,
			String link,
			String category,
			String description,
			String telephone,
			String address,
			String roadAddress,
			String mapx,
			String mapy
	) {
	}
}
