package com.swimpulse.subscription;

import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.pool.Pool;
import com.swimpulse.user.AppUser;
import jakarta.persistence.Entity;
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

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "event_id")
	private RegistrationEvent event;

	private Instant createdAt;

	protected Subscription() {
	}

	public Subscription(AppUser user, RegistrationEvent event) {
		this.user = user;
		this.event = event;
		this.pool = event.getPool();
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
		this.pool = event.getPool();
	}
}
