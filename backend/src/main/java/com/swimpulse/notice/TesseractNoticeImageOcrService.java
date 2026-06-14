package com.swimpulse.notice;

import com.swimpulse.common.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TesseractNoticeImageOcrService implements NoticeImageOcrService {
	private static final Logger log = LoggerFactory.getLogger(TesseractNoticeImageOcrService.class);
	private static final int DEFAULT_MAX_BODY_SIZE = 10 * 1024 * 1024;

	private final boolean enabled;
	private final String command;
	private final String languages;
	private final int maxImages;
	private final Duration timeout;
	private final boolean insecureSslFallbackEnabled;

	TesseractNoticeImageOcrService(
			boolean enabled,
			String command,
			String languages,
			int maxImages,
			Duration timeout,
			boolean insecureSslFallbackEnabled
	) {
		this.enabled = enabled;
		this.command = command;
		this.languages = languages;
		this.maxImages = Math.max(1, maxImages);
		this.timeout = timeout == null ? Duration.ofSeconds(15) : timeout.compareTo(Duration.ofSeconds(1)) < 0
				? Duration.ofSeconds(1)
				: timeout;
		this.insecureSslFallbackEnabled = insecureSslFallbackEnabled;
	}

	@Autowired
	public TesseractNoticeImageOcrService(
			@Value("${swimpulse.notice.ocr.enabled:true}") boolean enabled,
			@Value("${swimpulse.notice.ocr.command:tesseract}") String command,
			@Value("${swimpulse.notice.ocr.languages:kor+eng}") String languages,
			@Value("${swimpulse.notice.ocr.max-images:3}") int maxImages,
			@Value("${swimpulse.notice.ocr.timeout-ms:15000}") long timeoutMs,
			@Value("${swimpulse.notice.insecure-ssl-fallback:false}") boolean insecureSslFallbackEnabled
	) {
		this(enabled, command, languages, maxImages, Duration.ofMillis(timeoutMs), insecureSslFallbackEnabled);
	}

	@Override
	public NoticeImageOcrResult extractText(List<String> imageUrls) {
		if (!enabled) {
			return NoticeImageOcrResult.empty("OCR is disabled by configuration.");
		}

		List<String> targets = normalizeTargets(imageUrls);
		if (targets.isEmpty()) {
			return NoticeImageOcrResult.empty("No image URLs were provided.");
		}

		log.info("Notice OCR started. imageCount={} maxImages={} command={} languages={}",
				targets.size(), maxImages, command, languages);

		List<String> extractedTexts = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		int extractedImages = 0;
		for (String imageUrl : targets) {
			try {
				log.info("Notice OCR downloading image. url={}", imageUrl);
				byte[] imageBytes = downloadImage(imageUrl);
				log.info("Notice OCR image downloaded. url={} bytes={}", imageUrl, imageBytes.length);
				if (imageBytes.length == 0) {
					failures.add("Downloaded image was empty: " + imageUrl);
					continue;
				}
				String text = runTesseract(imageBytes, imageUrl);
				log.info("Notice OCR image processed. url={} textLength={}", imageUrl, text == null ? 0 : text.length());
				if (hasText(text)) {
					extractedTexts.add(text.trim());
					extractedImages++;
				} else {
					failures.add("OCR text was empty: " + imageUrl);
				}
			} catch (RuntimeException exception) {
				failures.add(imageUrl + " - " + exception.getMessage());
				log.warn("Notice OCR failed for image. url={} message={}", imageUrl, exception.getMessage());
			}
		}

		String combined = extractedTexts.stream()
				.filter(this::hasText)
				.collect(Collectors.joining("\n\n[OCR IMAGE TEXT]\n\n"));
		String reason = hasText(combined)
				? "OCR extracted text from " + extractedImages + " image(s)."
				: failures.isEmpty()
						? "OCR completed but no text was extracted."
						: "OCR completed without usable text. " + String.join(" | ", failures);
		log.info("Notice OCR completed. attemptedImages={} extractedImages={} textLength={}",
				targets.size(), extractedImages, combined.length());
		return new NoticeImageOcrResult(combined, targets.size(), extractedImages, reason);
	}

	List<String> normalizeTargets(List<String> imageUrls) {
		if (imageUrls == null) {
			return List.of();
		}
		Set<String> unique = new LinkedHashSet<>();
		for (String imageUrl : imageUrls) {
			if (hasText(imageUrl)) {
				unique.add(imageUrl.trim());
			}
			if (unique.size() >= maxImages) {
				break;
			}
		}
		return List.copyOf(unique);
	}

	byte[] downloadImage(String imageUrl) {
		try {
			return execute(imageUrl, true).bodyAsBytes();
		} catch (IOException exception) {
			if (insecureSslFallbackEnabled && isCertificateValidationFailure(exception)) {
				try {
					log.warn("Notice OCR image TLS certificate validation failed. Retrying without certificate validation. url={} message={}",
							imageUrl, exception.getMessage());
					return execute(imageUrl, false).bodyAsBytes();
				} catch (IOException fallbackException) {
					throw new BadRequestException("OCR image download failed after insecure SSL fallback: "
							+ fallbackException.getMessage());
				}
			}
			throw new BadRequestException("OCR image download failed: " + exception.getMessage());
		}
	}

	String runTesseract(byte[] imageBytes, String imageUrl) {
		Path tempDirectory = null;
		try {
			tempDirectory = Files.createTempDirectory("notice-ocr-");
			String extension = detectExtension(imageUrl);
			Path inputImage = tempDirectory.resolve("input" + extension);
			Path outputBase = tempDirectory.resolve("output");
			Files.write(inputImage, imageBytes);

			Process process = new ProcessBuilder(
					command,
					inputImage.toString(),
					outputBase.toString(),
					"-l",
					languages,
					"quiet"
			).redirectErrorStream(true).start();
			String processOutput;
			try (InputStream stream = process.getInputStream()) {
				boolean finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
				processOutput = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
				if (!finished) {
					process.destroyForcibly();
					throw new BadRequestException("OCR process timed out after " + timeout.toMillis() + "ms");
				}
			}
			if (process.exitValue() != 0) {
				throw new BadRequestException("OCR process exited with code " + process.exitValue()
						+ (hasText(processOutput) ? ": " + processOutput : ""));
			}

			Path outputTextFile = Path.of(outputBase + ".txt");
			if (!Files.exists(outputTextFile)) {
				return "";
			}
			return Files.readString(outputTextFile, StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new BadRequestException("OCR process failed: " + exception.getMessage());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BadRequestException("OCR process was interrupted.");
		} finally {
			TempCleanupResult cleanupResult = deleteRecursively(tempDirectory);
			if (tempDirectory != null) {
				if (cleanupResult.failures().isEmpty()) {
					log.info("Notice OCR temp cleanup completed. dir={} deletedEntries={}",
							tempDirectory, cleanupResult.deletedEntries());
				} else {
					log.warn("Notice OCR temp cleanup incomplete. dir={} deletedEntries={} failedEntries={}",
							tempDirectory, cleanupResult.deletedEntries(), cleanupResult.failures());
				}
			}
		}
	}

	private Connection.Response execute(String url, boolean validateTlsCertificates) throws IOException {
		Connection connection = Jsoup.connect(url)
				.userAgent("SwimPulseBot/1.0 (+https://swimpulse.local)")
				.timeout((int) timeout.toMillis())
				.followRedirects(true)
				.ignoreContentType(true)
				.maxBodySize(DEFAULT_MAX_BODY_SIZE);
		if (!validateTlsCertificates) {
			connection.sslSocketFactory(insecureSslSocketFactory());
		}
		return connection.execute();
	}

	private boolean isCertificateValidationFailure(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof SSLHandshakeException) {
				return true;
			}
			String message = current.getMessage();
			if (message != null && (message.contains("PKIX path building failed")
					|| message.contains("unable to find valid certification path")
					|| message.contains("certificate_unknown"))) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private SSLSocketFactory insecureSslSocketFactory() {
		try {
			TrustManager[] trustAllCerts = new TrustManager[] {
					new X509TrustManager() {
						@Override
						public void checkClientTrusted(X509Certificate[] chain, String authType) {
						}

						@Override
						public void checkServerTrusted(X509Certificate[] chain, String authType) {
						}

						@Override
						public X509Certificate[] getAcceptedIssuers() {
							return new X509Certificate[0];
						}
					}
			};
			SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, trustAllCerts, new SecureRandom());
			return sslContext.getSocketFactory();
		} catch (GeneralSecurityException exception) {
			throw new BadRequestException("Insecure SSL fallback could not be initialized: " + exception.getMessage());
		}
	}

	private String detectExtension(String imageUrl) {
		try {
			String path = URI.create(imageUrl).getPath();
			if (path == null) {
				return ".img";
			}
			int extensionIndex = path.lastIndexOf('.');
			if (extensionIndex < 0 || extensionIndex == path.length() - 1) {
				return ".img";
			}
			String extension = path.substring(extensionIndex);
			return extension.length() > 10 ? ".img" : extension;
		} catch (IllegalArgumentException exception) {
			return ".img";
		}
	}

	TempCleanupResult deleteRecursively(Path path) {
		if (path == null || !Files.exists(path)) {
			return new TempCleanupResult(0, List.of());
		}
		int deletedEntries = 0;
		List<String> failures = new ArrayList<>();
		try (var walk = Files.walk(path)) {
			for (Path current : walk.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
				try {
					if (Files.deleteIfExists(current)) {
						deletedEntries++;
					}
				} catch (IOException exception) {
					failures.add(current + " - " + exception.getMessage());
				}
			}
		} catch (IOException exception) {
			failures.add(path + " - " + exception.getMessage());
		}
		return new TempCleanupResult(deletedEntries, List.copyOf(failures));
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	record TempCleanupResult(int deletedEntries, List<String> failures) {
	}
}
