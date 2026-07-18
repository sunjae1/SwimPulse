package com.swimpulse.notice;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PoolNoticeRepository extends JpaRepository<PoolNotice, Long> {
	long countByPeriodsMigratedAtIsNull();

	long countByPeriodsMigrationErrorIsNotNull();

	long countByOcrStatus(NoticeOcrStatus ocrStatus);

	long countByExtractionStatus(NoticeExtractionStatus extractionStatus);

	Optional<PoolNotice> findByUrl(String url);

	List<PoolNotice> findByPool_IdOrderByIdAsc(Long poolId);

	List<PoolNotice> findTop20ByPoolIdOrderByIdDesc(Long poolId);

	List<PoolNotice> findTop20ByPoolIdAndHomepageRevisionOrderByIdDesc(Long poolId, int homepageRevision);

	@Query("""
			select notice
			from PoolNotice notice
			where notice.periodsMigratedAt is null
			order by notice.id
			""")
	List<PoolNotice> findPendingPeriodMigration(Pageable pageable);
}
