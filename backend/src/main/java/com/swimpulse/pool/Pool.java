package com.swimpulse.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "pools")
public class Pool {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 255)
	private String address;

	@Column(nullable = false, length = 50)
	private String district;

	@Column(nullable = false, length = 255)
	private String websiteUrl;

	@Column(nullable = false, length = 500)
	private String description;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected Pool() {
	}

	public Pool(String name, String address, String district, String websiteUrl, String description) {
		this.name = name;
		this.address = address;
		this.district = district;
		this.websiteUrl = websiteUrl;
		this.description = description;
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getAddress() {
		return address;
	}

	public String getDistrict() {
		return district;
	}

	public String getWebsiteUrl() {
		return websiteUrl;
	}

	public String getDescription() {
		return description;
	}
}
