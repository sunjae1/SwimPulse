package com.swimpulse.notice;

import com.swimpulse.event.RegistrationEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(
		name = "notice_registration_periods",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_notice_registration_period",
				columnNames = {"notice_id", "normalized_label", "starts_at", "ends_at"}
		)
)
public class NoticeRegistrationPeriodEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "notice_id", nullable = false)
	private PoolNotice notice;

	@Column(length = 255)
	private String label;

	@Column(nullable = false, length = 255)
	private String normalizedLabel;

	@Column(nullable = false)
	private Instant startsAt;

	@Column(nullable = false)
	private Instant endsAt;

	@Column(length = 1000)
	private String periodText;

	@Column(length = 100)
	private String source;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private NoticeRegistrationPeriodStatus status;

	@OneToOne(mappedBy = "noticeRegistrationPeriod", fetch = FetchType.LAZY)
	private RegistrationEvent registrationEvent;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	protected NoticeRegistrationPeriodEntity() {
	}

	public NoticeRegistrationPeriodEntity(PoolNotice notice, NoticeRegistrationPeriod period) {
		this.notice = notice;
		this.startsAt = period.startsAt();
		this.endsAt = period.endsAt();
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
		updateFrom(period);
	}

	public void updateFrom(NoticeRegistrationPeriod period) {
		this.label = truncate(period.label(), 255);
		this.normalizedLabel = normalizeLabel(period.label());
		this.periodText = truncate(period.periodText(), 1000);
		this.source = truncate(period.source(), 100);
		this.status = NoticeRegistrationPeriodStatus.ACTIVE;
		this.updatedAt = Instant.now();
	}

	public void markInactive() {
		this.status = NoticeRegistrationPeriodStatus.INACTIVE;
		this.updatedAt = Instant.now();
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public PoolNotice getNotice() {
		return notice;
	}

	public String getLabel() {
		return label;
	}

	public String getNormalizedLabel() {
		return normalizedLabel;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public String getPeriodText() {
		return periodText;
	}

	public String getSource() {
		return source;
	}

	public NoticeRegistrationPeriodStatus getStatus() {
		return status;
	}

	public RegistrationEvent getRegistrationEvent() {
		return registrationEvent;
	}

	public NoticeRegistrationPeriod toDto() {
		return new NoticeRegistrationPeriod(id, label, startsAt, endsAt, periodText, source);
	}

	public static String normalizeLabel(String label) {
		if (label == null) {
			return "";
		}
		return label.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", "")
				.trim();
	}

	private static String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}
}
