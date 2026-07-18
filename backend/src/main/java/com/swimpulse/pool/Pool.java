package com.swimpulse.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "pools")
public class Pool {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 100)
	private String normalizedName = "";

	@Column(nullable = false, length = 50)
	private String district;

	@Column(nullable = false, length = 500)
	private String description;

	private Integer completionYear;

	@Column(length = 20)
	private String indoorOutdoorTypeName;

	@Column(length = 100)
	private String ownerAgencyName;

	@Column(length = 100)
	private String managementAgencyName;

	@Column(length = 100)
	private String operatingOrganizationName;

	@Column(length = 50)
	private String contactNumber;

	@Column(precision = 6, scale = 2)
	private BigDecimal standardPoolLengthMeters;

	private Integer standardPoolLaneCount;

	@Column(length = 20)
	private String postalCode;

	@Column(length = 255)
	private String lotNumberAddress;

	@Column(nullable = false, length = 255)
	private String normalizedLotNumberAddress = "";

	@Column(length = 255)
	private String roadNameAddress;

	@Column(nullable = false, length = 255)
	private String normalizedRoadNameAddress = "";

	@Column(length = 255)
	private String homepageUrl;

	@Enumerated(EnumType.STRING)
	@Column(length = 40)
	private HomepageSource homepageSource;

	@Enumerated(EnumType.STRING)
	@Column(length = 40)
	private HomepageVerificationStatus homepageStatus = HomepageVerificationStatus.UNVERIFIED;

	@Column(nullable = false)
	private int homepageRevision = 1;

	private Instant homepageVerifiedAt;

	@Column(length = 500)
	private String homepageCandidateTitle;

	@Column(length = 500)
	private String homepageCandidateAddress;

	@Column(length = 500)
	private String homepageCandidateLink;

	private Instant lastNoticeDiscoveryAt;

	@Column(length = 500)
	private String imageUrl;

	private Double latitude;

	private Double longitude;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private GeocodeStatus geocodeStatus = GeocodeStatus.PENDING;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected Pool() {
	}

	public Pool(String name, String district, String description) {
		this.name = name;
		this.district = district;
		this.description = description;
		this.createdAt = Instant.now();
	}

	public Pool(
			String name,
			String district,
			String description,
			Integer completionYear,
			String indoorOutdoorTypeName,
			String ownerAgencyName,
			String managementAgencyName,
			String operatingOrganizationName,
			String contactNumber,
			BigDecimal standardPoolLengthMeters,
			Integer standardPoolLaneCount,
			String postalCode,
			String lotNumberAddress,
			String roadNameAddress,
			String homepageUrl
	) {
		this(name, district, description);
		updateFacilityDetails(
				completionYear,
				indoorOutdoorTypeName,
				ownerAgencyName,
				managementAgencyName,
				operatingOrganizationName,
				contactNumber,
				standardPoolLengthMeters,
				standardPoolLaneCount,
				postalCode,
				lotNumberAddress,
				roadNameAddress,
				homepageUrl
		);
	}

	public void updateFacilityDetails(
			Integer completionYear,
			String indoorOutdoorTypeName,
			String ownerAgencyName,
			String managementAgencyName,
			String operatingOrganizationName,
			String contactNumber,
			BigDecimal standardPoolLengthMeters,
			Integer standardPoolLaneCount,
			String postalCode,
			String lotNumberAddress,
			String roadNameAddress,
			String homepageUrl
	) {
		this.completionYear = completionYear;
		this.indoorOutdoorTypeName = indoorOutdoorTypeName;
		this.ownerAgencyName = ownerAgencyName;
		this.managementAgencyName = managementAgencyName;
		this.operatingOrganizationName = operatingOrganizationName;
		this.contactNumber = contactNumber;
		this.standardPoolLengthMeters = standardPoolLengthMeters;
		this.standardPoolLaneCount = standardPoolLaneCount;
		this.postalCode = postalCode;
		this.lotNumberAddress = lotNumberAddress;
		this.roadNameAddress = roadNameAddress;
		this.homepageUrl = homepageUrl;
	}

	public void updateHomepageUrl(String homepageUrl) {
		this.homepageUrl = homepageUrl;
	}

	public HomepageCorrection correctHomepage(String name, String homepageUrl) {
		String previousName = this.name;
		String previousHomepageUrl = this.homepageUrl;
		int previousRevision = getHomepageRevision();
		this.name = name;
		this.homepageUrl = homepageUrl;
		this.homepageSource = HomepageSource.MANUAL;
		this.homepageStatus = HomepageVerificationStatus.VERIFIED;
		this.homepageVerifiedAt = Instant.now();
		this.homepageCandidateTitle = null;
		this.homepageCandidateAddress = null;
		this.homepageCandidateLink = null;
		this.homepageRevision = previousRevision + 1;
		this.lastNoticeDiscoveryAt = null;
		return new HomepageCorrection(previousName, previousHomepageUrl, previousRevision, homepageRevision);
	}

	public void updateHomepageUrl(
			String homepageUrl,
			HomepageSource source,
			HomepageVerificationStatus status,
			String candidateTitle,
			String candidateAddress,
			String candidateLink
	) {
		updateHomepageUrl(homepageUrl);
		recordHomepageVerification(source, status, candidateTitle, candidateAddress, candidateLink);
	}

	public void recordHomepageVerification(
			HomepageSource source,
			HomepageVerificationStatus status,
			String candidateTitle,
			String candidateAddress,
			String candidateLink
	) {
		this.homepageSource = source == null ? HomepageSource.UNKNOWN : source;
		this.homepageStatus = status == null ? HomepageVerificationStatus.UNVERIFIED : status;
		this.homepageVerifiedAt = Instant.now();
		this.homepageCandidateTitle = truncate(candidateTitle, 500);
		this.homepageCandidateAddress = truncate(candidateAddress, 500);
		this.homepageCandidateLink = truncate(candidateLink, 500);
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getNormalizedName() {
		return normalizedName;
	}

	public String getDistrict() {
		return district;
	}

	public String getDescription() {
		return description;
	}

	public Integer getCompletionYear() {
		return completionYear;
	}

	public String getIndoorOutdoorTypeName() {
		return indoorOutdoorTypeName;
	}

	public String getOwnerAgencyName() {
		return ownerAgencyName;
	}

	public String getManagementAgencyName() {
		return managementAgencyName;
	}

	public String getOperatingOrganizationName() {
		return operatingOrganizationName;
	}

	public String getContactNumber() {
		return contactNumber;
	}

	public BigDecimal getStandardPoolLengthMeters() {
		return standardPoolLengthMeters;
	}

	public Integer getStandardPoolLaneCount() {
		return standardPoolLaneCount;
	}

	public String getPostalCode() {
		return postalCode;
	}

	public String getLotNumberAddress() {
		return lotNumberAddress;
	}

	public String getNormalizedLotNumberAddress() {
		return normalizedLotNumberAddress;
	}

	public String getRoadNameAddress() {
		return roadNameAddress;
	}

	public String getNormalizedRoadNameAddress() {
		return normalizedRoadNameAddress;
	}

	public String getHomepageUrl() {
		return homepageUrl;
	}

	public HomepageSource getHomepageSource() {
		return homepageSource;
	}

	public HomepageVerificationStatus getHomepageStatus() {
		return homepageStatus == null ? HomepageVerificationStatus.UNVERIFIED : homepageStatus;
	}

	public int getHomepageRevision() {
		return homepageRevision < 1 ? 1 : homepageRevision;
	}

	public Instant getHomepageVerifiedAt() {
		return homepageVerifiedAt;
	}

	public String getHomepageCandidateTitle() {
		return homepageCandidateTitle;
	}

	public String getHomepageCandidateAddress() {
		return homepageCandidateAddress;
	}

	public String getHomepageCandidateLink() {
		return homepageCandidateLink;
	}

	public Instant getLastNoticeDiscoveryAt() {
		return lastNoticeDiscoveryAt;
	}

	public void markNoticeDiscoveryAttempt() {
		this.lastNoticeDiscoveryAt = Instant.now();
	}

	public String getImageUrl() {
		return imageUrl;
	}

	public void updateImageUrl(String imageUrl) {
		this.imageUrl = truncate(imageUrl, 500);
	}

	public Double getLatitude() {
		return latitude;
	}

	public Double getLongitude() {
		return longitude;
	}

	public GeocodeStatus getGeocodeStatus() {
		return geocodeStatus;
	}

	public String resolveGeocodeAddress() {
		if (hasText(roadNameAddress)) {
			return roadNameAddress;
		}
		if (hasText(lotNumberAddress)) {
			return lotNumberAddress;
		}
		return null;
	}

	public void markGeocodeSuccess(double latitude, double longitude) {
		this.latitude = latitude;
		this.longitude = longitude;
		this.geocodeStatus = GeocodeStatus.SUCCESS;
	}

	public static Pool fromLocationCandidate(
			String name,
			String address,
			String roadNameAddress,
			String lotNumberAddress,
			String homepageUrl,
			double latitude,
			double longitude
	) {
		String normalizedAddress = hasTextValue(roadNameAddress) ? roadNameAddress : lotNumberAddress;
		if (!hasTextValue(normalizedAddress)) {
			normalizedAddress = address;
		}
		Pool pool = new Pool(
				name,
				resolveDistrict(normalizedAddress),
				"네이버 지역 검색 후보를 사용자가 확인해 추가한 시설입니다."
		);
		pool.updateFacilityDetails(
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				lotNumberAddress,
				roadNameAddress,
				homepageUrl
		);
		if (hasTextValue(homepageUrl)) {
			pool.recordHomepageVerification(
					HomepageSource.USER_LOCATION_CANDIDATE,
					HomepageVerificationStatus.VERIFIED,
					name,
					normalizedAddress,
					homepageUrl
			);
		}
		pool.markGeocodeSuccess(latitude, longitude);
		return pool;
	}

	private static String resolveDistrict(String address) {
		if (!hasTextValue(address)) {
			return "미확인";
		}
		String[] tokens = address.trim().split("\\s+");
		for (String token : tokens) {
			if (token.endsWith("구") || token.endsWith("군") || token.endsWith("시")) {
				return token;
			}
		}
		return tokens[0];
	}

	private static boolean hasTextValue(String value) {
		return value != null && !value.isBlank();
	}

	private static String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}

	public void markGeocodeFailed() {
		this.geocodeStatus = GeocodeStatus.FAILED;
	}

	public void markGeocodePending() {
		this.geocodeStatus = GeocodeStatus.PENDING;
	}

	@PrePersist
	@PreUpdate
	private void refreshNormalizedSearchFields() {
		this.normalizedName = PoolSearchNormalizer.normalize(name);
		this.normalizedLotNumberAddress = PoolSearchNormalizer.normalize(lotNumberAddress);
		this.normalizedRoadNameAddress = PoolSearchNormalizer.normalize(roadNameAddress);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public record HomepageCorrection(
			String previousName,
			String previousHomepageUrl,
			int previousRevision,
			int currentRevision
	) {
	}
}
