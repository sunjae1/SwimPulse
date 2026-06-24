package com.swimpulse.notice;

import com.swimpulse.pool.Pool;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolNoticeSourceRepository extends JpaRepository<PoolNoticeSource, Long> {
	Optional<PoolNoticeSource> findByPoolAndSourceUrl(Pool pool, String sourceUrl);

	List<PoolNoticeSource> findByPoolAndStatusOrderByIdAsc(Pool pool, NoticeSourceStatus status);

	List<PoolNoticeSource> findByPoolAndStatusInOrderByIdAsc(
			Pool pool,
			Collection<NoticeSourceStatus> statuses
	);

	List<PoolNoticeSource> findByPoolOrderByIdAsc(Pool pool);

	List<PoolNoticeSource> findByPoolAndStatusAndLastScannedAtBeforeOrderByIdAsc(
			Pool pool,
			NoticeSourceStatus status,
			Instant lastScannedAt
	);

	boolean existsByPoolAndStatus(Pool pool, NoticeSourceStatus status);

	long countByStatus(NoticeSourceStatus status);
}
