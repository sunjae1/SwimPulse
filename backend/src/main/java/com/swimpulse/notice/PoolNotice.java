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
import jakarta.persistence.Table;
import java.time.Instant;

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
		this.pool = pool;
		this.title = title;
		this.url = url;
		this.rawText = rawText;
		this.extractionStatus = extractionStatus;
		this.confidence = confidence;
		this.registrationStartsAt = registrationStartsAt;
		this.registrationEndsAt = registrationEndsAt;
		this.reason = reason;
		this.createdAt = Instant.now();
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

	public String getReason() {
		return reason;
	}
}
