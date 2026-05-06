package com.swimpulse.notice;

import com.swimpulse.pool.Pool;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolNoticeSourceRepository extends JpaRepository<PoolNoticeSource, Long> {
	Optional<PoolNoticeSource> findByPoolAndSourceUrl(Pool pool, String sourceUrl);
}
