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

	private Instant lastScannedAt;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected PoolNoticeSource() {
	}

	public PoolNoticeSource(Pool pool, String sourceUrl, NoticeSourceType sourceType) {
		this.pool = pool;
		this.sourceUrl = sourceUrl;
		this.sourceType = sourceType;
		this.status = NoticeSourceStatus.ACTIVE;
		this.createdAt = Instant.now();
	}

	public void markScanned(NoticeSourceStatus status) {
		this.status = status;
		this.lastScannedAt = Instant.now();
	}
}
