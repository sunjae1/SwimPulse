package com.swimpulse.pool;

import com.swimpulse.common.BadRequestException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JsoupPoolImagePageClient implements PoolImagePageClient {
	private final HttpClient httpClient;
	private final Duration timeout;

	public JsoupPoolImagePageClient(
			@Value("${swimpulse.pool.image-enrichment.timeout-ms:5000}") long timeoutMs
	) {
		this.timeout = Duration.ofMillis(timeoutMs);
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(timeout)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	@Override
	public Document fetch(String url) {
		try {
			return Jsoup.connect(url)
					.userAgent("SwimPulseBot/1.0 (+https://swimpulse.local)")
					.timeout((int) timeout.toMillis())
					.followRedirects(true)
					.get();
		} catch (IOException exception) {
			throw new BadRequestException("Homepage image metadata fetch failed: " + exception.getMessage());
		}
	}

	@Override
	public ImageProbe probe(String imageUrl) {
		try {
			HttpResponse<Void> response = httpClient.send(
					request(imageUrl, "HEAD"),
					HttpResponse.BodyHandlers.discarding()
			);
			if (response.statusCode() == 405 || response.statusCode() == 403) {
				response = httpClient.send(request(imageUrl, "GET"), HttpResponse.BodyHandlers.discarding());
			}
			String contentType = response.headers().firstValue("content-type").orElse("");
			long contentLength = response.headers()
					.firstValueAsLong("content-length")
					.orElse(-1L);
			return contentType.toLowerCase().startsWith("image/")
					? ImageProbe.image(contentType, contentLength)
					: ImageProbe.notImage(contentType, contentLength);
		} catch (IOException exception) {
			throw new BadRequestException("Image URL validation failed: " + exception.getMessage());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BadRequestException("Image URL validation interrupted.");
		} catch (IllegalArgumentException exception) {
			throw new BadRequestException("Invalid image URL: " + exception.getMessage());
		}
	}

	private HttpRequest request(String imageUrl, String method) {
		return HttpRequest.newBuilder(URI.create(imageUrl))
				.method(method, HttpRequest.BodyPublishers.noBody())
				.timeout(timeout)
				.header("User-Agent", "SwimPulseBot/1.0 (+https://swimpulse.local)")
				.build();
	}
}
