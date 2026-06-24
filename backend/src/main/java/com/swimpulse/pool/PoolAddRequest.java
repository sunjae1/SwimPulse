package com.swimpulse.pool;

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
@Table(name = "pool_add_requests")
public class PoolAddRequest {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "requested_by_user_id", nullable = false)
	private AppUser requestedBy;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(length = 255)
	private String category;

	@Column(length = 255)
	private String address;

	@Column(length = 255)
	private String roadAddress;

	@Column(length = 500)
	private String homepageUrl;

	private Double latitude;

	private Double longitude;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PoolAddRequestStatus status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "approved_pool_id")
	private Pool approvedPool;

	@Column(length = 1000)
	private String adminNote;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant reviewedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reviewed_by_admin_id")
	private AppUser reviewedBy;

	protected PoolAddRequest() {
	}

	public PoolAddRequest(AppUser requestedBy, CreatePoolFromLocationCandidateRequest request) {
		this.requestedBy = requestedBy;
		this.title = truncate(request.title(), 100);
		this.category = truncate(request.category(), 255);
		this.address = truncate(request.address(), 255);
		this.roadAddress = truncate(request.roadAddress(), 255);
		this.homepageUrl = truncate(request.link(), 500);
		this.latitude = request.latitude();
		this.longitude = request.longitude();
		this.status = PoolAddRequestStatus.PENDING;
		this.createdAt = Instant.now();
	}

	public void approve(Pool pool, AppUser admin) {
		this.status = PoolAddRequestStatus.APPROVED;
		this.approvedPool = pool;
		this.reviewedBy = admin;
		this.reviewedAt = Instant.now();
		this.adminNote = null;
	}

	public void merge(Pool pool, AppUser admin, String note) {
		this.status = PoolAddRequestStatus.MERGED;
		this.approvedPool = pool;
		this.reviewedBy = admin;
		this.reviewedAt = Instant.now();
		this.adminNote = truncate(note, 1000);
	}

	public void reject(AppUser admin, String note) {
		this.status = PoolAddRequestStatus.REJECTED;
		this.reviewedBy = admin;
		this.reviewedAt = Instant.now();
		this.adminNote = truncate(note, 1000);
	}

	public CreatePoolFromLocationCandidateRequest toLocationCandidateRequest() {
		return new CreatePoolFromLocationCandidateRequest(
				title,
				category,
				address,
				roadAddress,
				homepageUrl,
				latitude,
				longitude
		);
	}

	public Long getId() {
		return id;
	}

	public AppUser getRequestedBy() {
		return requestedBy;
	}

	public String getTitle() {
		return title;
	}

	public String getCategory() {
		return category;
	}

	public String getAddress() {
		return address;
	}

	public String getRoadAddress() {
		return roadAddress;
	}

	public String getHomepageUrl() {
		return homepageUrl;
	}

	public Double getLatitude() {
		return latitude;
	}

	public Double getLongitude() {
		return longitude;
	}

	public PoolAddRequestStatus getStatus() {
		return status;
	}

	public Pool getApprovedPool() {
		return approvedPool;
	}

	public String getAdminNote() {
		return adminNote;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getReviewedAt() {
		return reviewedAt;
	}

	public AppUser getReviewedBy() {
		return reviewedBy;
	}

	private static String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}
}
