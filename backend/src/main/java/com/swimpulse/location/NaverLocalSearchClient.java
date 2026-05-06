package com.swimpulse.location;

import com.swimpulse.common.BadRequestException;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class NaverLocalSearchClient {
	private static final String NAVER_LOCAL_SEARCH_URL = "https://openapi.naver.com/v1/search/local.json";
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");

	private final RestClient restClient;
	private final String clientId;
	private final String clientSecret;

	public NaverLocalSearchClient(
			RestClient.Builder restClientBuilder,
			@Value("${swimpulse.naver.search.client-id:}") String clientId,
			@Value("${swimpulse.naver.search.client-secret:}") String clientSecret
	) {
		this.restClient = restClientBuilder.build();
		this.clientId = clientId;
		this.clientSecret = clientSecret;
	}

	public List<LocationSearchCandidate> search(String query, int display) {
		if (!isConfigured()) {
			throw new BadRequestException("Naver Search API credentials are not configured.");
		}

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
			throw new BadRequestException("Naver local search request failed: "
					+ exception.getStatusCode().value() + " " + exception.getStatusText());
		}

		if (response == null || response.items() == null) {
			return List.of();
		}

		return response.items()
				.stream()
				.map(item -> LocationSearchCandidate.basic(
						stripHtml(item.title()),
						stripHtml(item.category()),
						emptyToNull(item.address()),
						emptyToNull(item.roadAddress()),
						emptyToNull(item.link())
				))
				.toList();
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
