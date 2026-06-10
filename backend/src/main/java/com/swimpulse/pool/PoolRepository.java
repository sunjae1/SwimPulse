package com.swimpulse.pool;

import com.swimpulse.notice.NoticeSourceStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PoolRepository extends JpaRepository<Pool, Long> {
	List<Pool> findAllByOrderByNameAsc();

	List<Pool> findTop10ByOrderByIdAsc();

	Optional<Pool> findByName(String name);

	List<Pool> findTop50ByGeocodeStatusOrderByIdAsc(GeocodeStatus geocodeStatus);

	@Query("""
			select pool
			from Pool pool
			where pool.homepageUrl is not null
			  and pool.homepageUrl <> ''
			  and (
					exists (
						select source.id
						from PoolNoticeSource source
						where source.pool = pool
						  and source.status = :candidateStatus
					)
					or exists (
						select source.id
						from PoolNoticeSource source
						where source.pool = pool
						  and source.status = :failedStatus
						  and source.lastScannedAt < :failedRetryBefore
					)
					or (
						(pool.lastNoticeDiscoveryAt is null or pool.lastNoticeDiscoveryAt < :discoveryBefore)
						and not exists (
							select source.id
							from PoolNoticeSource source
							where source.pool = pool
							  and source.status = :verifiedStatus
						)
					)
			  )
			order by pool.id
			""")
	List<Pool> findPoolsNeedingNoticeSourceVerification(
			@Param("candidateStatus") NoticeSourceStatus candidateStatus,
			@Param("failedStatus") NoticeSourceStatus failedStatus,
			@Param("verifiedStatus") NoticeSourceStatus verifiedStatus,
			@Param("failedRetryBefore") Instant failedRetryBefore,
			@Param("discoveryBefore") Instant discoveryBefore,
			Pageable pageable
	);

	@Query("""
			select pool
			from Pool pool
			where pool.normalizedName in :normalizedNames
			   or pool.normalizedRoadNameAddress in :normalizedRoadAddresses
			   or pool.normalizedLotNumberAddress in :normalizedLotAddresses
			order by pool.id
			""")
	List<Pool> findMatchingCandidates(
			@Param("normalizedNames") Collection<String> normalizedNames,
			@Param("normalizedRoadAddresses") Collection<String> normalizedRoadAddresses,
			@Param("normalizedLotAddresses") Collection<String> normalizedLotAddresses
	);

	@Query(value = """
			select pool.*
			from pools pool
			where pool.latitude is not null
			  and pool.longitude is not null
			  and ST_Distance_Sphere(
					POINT(pool.longitude, pool.latitude),
					POINT(:longitude, :latitude)
			  ) <= :distanceMeters
			order by ST_Distance_Sphere(
					POINT(pool.longitude, pool.latitude),
					POINT(:longitude, :latitude)
			)
			limit 1
			""", nativeQuery = true)
	Optional<Pool> findNearestWithinDistance(
			@Param("latitude") double latitude,
			@Param("longitude") double longitude,
			@Param("distanceMeters") double distanceMeters
	);
}
