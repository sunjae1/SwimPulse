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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "pool_notice_sources")
public class PoolNoticeSource {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "pool_id", nullable = false)
	private Pool pool;

	@Column(nullable = false, length = 500)
	private String sourceUrl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private NoticeSourceType sourceType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private NoticeSourceStatus status;

	@Column(nullable = false)
	private int homepageRevision;

	private Instant lastScannedAt;

	private Instant lastSuccessAt;

	@Column(nullable = false)
	private int failureCount;

	@Column(length = 1000)
	private String lastError;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected PoolNoticeSource() {
	}

	public PoolNoticeSource(Pool pool, String sourceUrl, NoticeSourceType sourceType) {
		this.pool = pool;
		this.sourceUrl = NoticeSourceUrlNormalizer.normalize(sourceUrl);
		this.sourceType = sourceType;
		this.status = NoticeSourceStatus.CANDIDATE;
		this.homepageRevision = pool.getHomepageRevision();
		this.failureCount = 0;
		this.createdAt = Instant.now();
	}

	public void markVerified() {
		this.status = NoticeSourceStatus.VERIFIED;
		this.failureCount = 0;
		this.lastError = null;
		this.lastScannedAt = Instant.now();
		this.lastSuccessAt = this.lastScannedAt;
	}

	public void prepareForHomepageRevision(int revision) {
		this.homepageRevision = Math.max(1, revision);
		this.status = NoticeSourceStatus.CANDIDATE;
		this.failureCount = 0;
		this.lastError = null;
	}

	public void markInactive() {
		this.status = NoticeSourceStatus.INACTIVE;
		this.failureCount = 0;
		this.lastError = null;
		this.lastScannedAt = Instant.now();
	}

	public void markFailure(String message, int failureThreshold) {
		this.failureCount++;
		this.lastError = truncate(message, 1000);
		this.lastScannedAt = Instant.now();
		if (this.failureCount >= failureThreshold) {
			this.status = NoticeSourceStatus.FAILED;
		}
	}

	public Long getId() {
		return id;
	}

	public Pool getPool() {
		return pool;
	}

	public String getSourceUrl() {
		return sourceUrl;
	}

	public NoticeSourceType getSourceType() {
		return sourceType;
	}

	public NoticeSourceStatus getStatus() {
		return status;
	}

	public int getHomepageRevision() {
		return homepageRevision < 1 ? 1 : homepageRevision;
	}

	public Instant getLastScannedAt() {
		return lastScannedAt;
	}

	public Instant getLastSuccessAt() {
		return lastSuccessAt;
	}

	public int getFailureCount() {
		return failureCount;
	}

	public String getLastError() {
		return lastError;
	}

	private static String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}
}
