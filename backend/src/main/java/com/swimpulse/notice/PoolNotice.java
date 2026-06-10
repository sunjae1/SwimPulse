package com.swimpulse.notice;

import com.swimpulse.pool.Pool;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pool_notices")
public class PoolNotice {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "pool_id", nullable = false)
	private Pool pool;

	@Column(nullable = false, length = 255)
	private String title;

	@Column(nullable = false, length = 500)
	private String url;

	private Instant publishedAt;

	@Lob
	@Column(columnDefinition = "LONGTEXT")
	private String rawText;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private NoticeExtractionStatus extractionStatus;

	private Double confidence;

	private Instant registrationStartsAt;

	private Instant registrationEndsAt;

	@Lob
	@Column(columnDefinition = "LONGTEXT")
	private String registrationPeriodsJson;

	@Column(nullable = false)
	private int parserVersion;

	private Instant lastAnalyzedAt;

	@OneToMany(mappedBy = "notice", fetch = FetchType.LAZY)
	private List<NoticeRegistrationPeriodEntity> registrationPeriods = new ArrayList<>();

	private Instant periodsMigratedAt;

	@Column(length = 1000)
	private String periodsMigrationError;

	@Column(length = 500)
	private String reason;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected PoolNotice() {
	}

	public PoolNotice(
			Pool pool,
			String title,
			String url,
			String rawText,
			NoticeExtractionStatus extractionStatus,
			Double confidence,
			Instant registrationStartsAt,
			Instant registrationEndsAt,
			String reason
	) {
		this(pool, title, url, rawText, extractionStatus, confidence, registrationStartsAt, registrationEndsAt, reason, null);
	}

	public PoolNotice(
			Pool pool,
			String title,
			String url,
			String rawText,
			NoticeExtractionStatus extractionStatus,
			Double confidence,
			Instant registrationStartsAt,
			Instant registrationEndsAt,
			String reason,
			String registrationPeriodsJson
	) {
		this.pool = pool;
		this.title = normalizeTitle(title);
		this.url = NoticeSourceUrlNormalizer.normalize(url);
		this.rawText = rawText;
		this.extractionStatus = extractionStatus;
		this.confidence = confidence;
		this.registrationStartsAt = registrationStartsAt;
		this.registrationEndsAt = registrationEndsAt;
		this.reason = reason;
		this.registrationPeriodsJson = registrationPeriodsJson;
		this.parserVersion = 0;
		this.createdAt = Instant.now();
	}

	public void updateExtraction(
			String title,
			String rawText,
			NoticeExtractionStatus extractionStatus,
			Double confidence,
			Instant registrationStartsAt,
			Instant registrationEndsAt,
			String reason,
			String registrationPeriodsJson
	) {
		this.title = normalizeTitle(title);
		this.rawText = rawText;
		this.extractionStatus = extractionStatus;
		this.confidence = confidence;
		this.registrationStartsAt = registrationStartsAt;
		this.registrationEndsAt = registrationEndsAt;
		this.reason = reason;
		this.registrationPeriodsJson = registrationPeriodsJson;
	}

	public void normalizeUrl() {
		this.url = NoticeSourceUrlNormalizer.normalize(this.url);
	}

	public void markAnalyzed(int parserVersion) {
		this.parserVersion = parserVersion;
		this.lastAnalyzedAt = Instant.now();
	}

	public void markPeriodsMigrated() {
		this.periodsMigratedAt = Instant.now();
		this.periodsMigrationError = null;
	}

	public void markPeriodsMigrationFailed(String message) {
		this.periodsMigratedAt = Instant.now();
		this.periodsMigrationError = truncate(message, 1000);
	}

	public Long getId() {
		return id;
	}

	public Pool getPool() {
		return pool;
	}

	public String getTitle() {
		return title;
	}

	public String getUrl() {
		return url;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public NoticeExtractionStatus getExtractionStatus() {
		return extractionStatus;
	}

	public Double getConfidence() {
		return confidence;
	}

	public Instant getRegistrationStartsAt() {
		return registrationStartsAt;
	}

	public Instant getRegistrationEndsAt() {
		return registrationEndsAt;
	}

	public String getRegistrationPeriodsJson() {
		return registrationPeriodsJson;
	}

	public int getParserVersion() {
		return parserVersion;
	}

	public Instant getLastAnalyzedAt() {
		return lastAnalyzedAt;
	}

	public List<NoticeRegistrationPeriodEntity> getRegistrationPeriods() {
		return registrationPeriods;
	}

	public Instant getPeriodsMigratedAt() {
		return periodsMigratedAt;
	}

	public String getPeriodsMigrationError() {
		return periodsMigrationError;
	}

	public String getReason() {
		return reason;
	}

	private static String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}

	private static String normalizeTitle(String value) {
		if (value == null) {
			return null;
		}
		return truncate(value.replaceAll("\\s+", " ").trim(), 255);
	}
}
