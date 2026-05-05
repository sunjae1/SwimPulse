package com.swimpulse.subscription;

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
		uniqueConstraints = @UniqueConstraint(name = "uk_subscription_user_pool", columnNames = {"user_id", "pool_id"})
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

	private Instant createdAt;

	protected Subscription() {
	}

	public Subscription(AppUser user, Pool pool) {
		this.user = user;
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

	public Instant getCreatedAt() {
		return createdAt;
	}
}
