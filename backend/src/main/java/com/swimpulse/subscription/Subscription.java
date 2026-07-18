package com.swimpulse.subscription;

import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.pool.Pool;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
		name = "subscriptions",
		uniqueConstraints = @UniqueConstraint(name = "uk_subscription_user_event", columnNames = {"user_id", "event_id"})
)
public class Subscription {
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

	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SubscriptionReviewStatus reviewStatus = SubscriptionReviewStatus.ACTIVE;

	private Instant reviewRequestedAt;

	private Instant reviewedAt;

	@Column(length = 500)
	private String reviewReason;

	protected Subscription() {
	}

	public Subscription(AppUser user, RegistrationEvent event) {
		this(user, event, event.getPool());
	}

	public Subscription(AppUser user, RegistrationEvent event, Pool pool) {
		this.user = user;
		this.event = event;
		this.pool = pool;
		this.createdAt = Instant.now();
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

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void reassignEvent(RegistrationEvent event) {
		this.event = event;
		this.reviewStatus = SubscriptionReviewStatus.CONFIRMED;
		this.reviewedAt = Instant.now();
	}

	public void requireReview(String reason) {
		this.reviewStatus = SubscriptionReviewStatus.REVIEW_REQUIRED;
		this.reviewRequestedAt = Instant.now();
		this.reviewedAt = null;
		this.reviewReason = truncate(reason, 500);
	}

	public void confirmReview() {
		this.reviewStatus = SubscriptionReviewStatus.CONFIRMED;
		this.reviewedAt = Instant.now();
	}

	public void invalidate(String reason) {
		this.reviewStatus = SubscriptionReviewStatus.INVALIDATED;
		this.reviewedAt = Instant.now();
		this.reviewReason = truncate(reason, 500);
	}

	public boolean allowsNotifications() {
		return getReviewStatus() == SubscriptionReviewStatus.ACTIVE
				|| getReviewStatus() == SubscriptionReviewStatus.CONFIRMED;
	}

	public SubscriptionReviewStatus getReviewStatus() {
		return reviewStatus == null ? SubscriptionReviewStatus.ACTIVE : reviewStatus;
	}

	public Instant getReviewRequestedAt() {
		return reviewRequestedAt;
	}

	public Instant getReviewedAt() {
		return reviewedAt;
	}

	public String getReviewReason() {
		return reviewReason;
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}
}
