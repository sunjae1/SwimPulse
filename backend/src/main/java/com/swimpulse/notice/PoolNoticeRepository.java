package com.swimpulse.notice;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolNoticeRepository extends JpaRepository<PoolNotice, Long> {
	Optional<PoolNotice> findByUrl(String url);

	List<PoolNotice> findTop20ByPoolIdOrderByIdDesc(Long poolId);
}
