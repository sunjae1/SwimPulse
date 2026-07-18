package com.swimpulse.notification;

import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.pool.Pool;
import com.swimpulse.subscription.Subscription;
import com.swimpulse.user.AppUser;
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
@Table(name = "notifications")
public class Notification {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "pool_id", nullable = false)
	private Pool pool;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_id", nullable = false)
	private RegistrationEvent event;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "subscription_id")
	private Subscription subscription;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private NotificationType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private NotificationStatus status;

	@Column(nullable = false, length = 120)
	private String title;

	@Column(nullable = false, length = 500)
	private String message;

	@Column(length = 255)
	private String fcmMessageId;

	@Column(length = 500)
	private String failureReason;

	@Column(length = 120)
	private String dedupeKey;

	@Column(nullable = false)
	private int attempts;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant queuedAt;

	private Instant processingStartedAt;

	private Instant sentAt;

	private Instant readAt;

	protected Notification() {
	}

	public Notification(AppUser user, Pool pool, RegistrationEvent event, NotificationType type, String title, String message) {
		this(user, pool, event, type, title, message, null);
	}

	public Notification(AppUser user, Pool pool, RegistrationEvent event, NotificationType type, String title, String message, String dedupeKey) {
		this(user, pool, event, null, type, title, message, dedupeKey);
	}

	public Notification(
			Subscription subscription,
			NotificationType type,
			String title,
			String message,
			String dedupeKey
	) {
		this(
				subscription.getUser(),
				subscription.getPool(),
				subscription.getEvent(),
				subscription,
				type,
				title,
				message,
				dedupeKey
		);
	}

	private Notification(
			AppUser user,
			Pool pool,
			RegistrationEvent event,
			Subscription subscription,
			NotificationType type,
			String title,
			String message,
			String dedupeKey
	) {
		this.user = user;
		this.pool = pool;
		this.event = event;
		this.subscription = subscription;
		this.type = type;
		this.title = title;
		this.message = message;
		this.dedupeKey = dedupeKey;
		this.status = NotificationStatus.QUEUED;
		this.createdAt = Instant.now();
		this.queuedAt = this.createdAt;
	}

	public boolean markSending() {
		if (this.status != NotificationStatus.QUEUED) {
			return false;
		}
		this.status = NotificationStatus.SENDING;
		this.attempts++;
		this.processingStartedAt = Instant.now();
		return true;
	}

	public void markSent(String fcmMessageId) {
		this.status = NotificationStatus.SENT;
		this.fcmMessageId = fcmMessageId;
		this.failureReason = null;
		this.sentAt = Instant.now();
		this.processingStartedAt = null;
	}

	public void markFailed(String failureReason) {
		this.status = NotificationStatus.FAILED;
		this.failureReason = failureReason;
		this.processingStartedAt = null;
	}

	public void markQueued() {
		this.status = NotificationStatus.QUEUED;
		this.queuedAt = Instant.now();
		this.processingStartedAt = null;
	}

	public boolean cancelIfQueued() {
		if (this.status != NotificationStatus.QUEUED) {
			return false;
		}
		this.status = NotificationStatus.CANCELLED;
		this.processingStartedAt = null;
		return true;
	}

	public void markRead() {
		this.readAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public AppUser getUser() {
		return user;
	}

	public Pool getPool() {
		return pool;
	}

	public RegistrationEvent getEvent() {
		return event;
	}

	public Subscription getSubscription() {
		return subscription;
	}

	public NotificationType getType() {
		return type;
	}

	public NotificationStatus getStatus() {
		return status;
	}

	public String getTitle() {
		return title;
	}

	public String getMessage() {
		return message;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public String getDedupeKey() {
		return dedupeKey;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getQueuedAt() {
		return queuedAt;
	}

	public Instant getProcessingStartedAt() {
		return processingStartedAt;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public Instant getReadAt() {
		return readAt;
	}
}
