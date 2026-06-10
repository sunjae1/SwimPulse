package com.swimpulse.event;

import com.swimpulse.pool.Pool;
import com.swimpulse.notice.NoticeRegistrationPeriodEntity;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
		name = "registration_events",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_registration_event_pool_title_period",
				columnNames = {"pool_id", "title", "registration_starts_at", "registration_ends_at"}
		)
)
public class RegistrationEvent {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "pool_id", nullable = false)
	private Pool pool;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "notice_registration_period_id", unique = true)
	private NoticeRegistrationPeriodEntity noticeRegistrationPeriod;

	@Column(nullable = false, length = 120)
	private String title;

	@Column(nullable = false)
	private Instant registrationStartsAt;

	@Column(nullable = false)
	private Instant registrationEndsAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private EventStatus status;

	@Column(nullable = false)
	private boolean reminderQueued;

	@Column(nullable = false)
	private boolean startQueued;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	protected RegistrationEvent() {
	}

	public RegistrationEvent(Pool pool, String title, Instant registrationStartsAt, Instant registrationEndsAt, EventStatus status) {
		this(pool, null, title, registrationStartsAt, registrationEndsAt, status);
	}

	public RegistrationEvent(
			Pool pool,
			NoticeRegistrationPeriodEntity noticeRegistrationPeriod,
			String title,
			Instant registrationStartsAt,
			Instant registrationEndsAt,
			EventStatus status
	) {
		this.pool = pool;
		this.noticeRegistrationPeriod = noticeRegistrationPeriod;
		this.title = title;
		this.registrationStartsAt = registrationStartsAt;
		this.registrationEndsAt = registrationEndsAt;
		this.status = status;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = Instant.now();
	}

	public void changeStatus(EventStatus status) {
		this.status = status;
	}

	public void markReminderQueued() {
		this.reminderQueued = true;
	}

	public void markStartQueued() {
		this.startQueued = true;
	}

	public Long getId() {
		return id;
	}

	public Pool getPool() {
		return pool;
	}

	public NoticeRegistrationPeriodEntity getNoticeRegistrationPeriod() {
		return noticeRegistrationPeriod;
	}

	public void assignNoticeRegistrationPeriod(NoticeRegistrationPeriodEntity noticeRegistrationPeriod) {
		this.noticeRegistrationPeriod = noticeRegistrationPeriod;
	}

	public String getTitle() {
		return title;
	}

	public Instant getRegistrationStartsAt() {
		return registrationStartsAt;
	}

	public Instant getRegistrationEndsAt() {
		return registrationEndsAt;
	}

	public EventStatus getStatus() {
		return status;
	}

	public boolean isReminderQueued() {
		return reminderQueued;
	}

	public boolean isStartQueued() {
		return startQueued;
	}
}
