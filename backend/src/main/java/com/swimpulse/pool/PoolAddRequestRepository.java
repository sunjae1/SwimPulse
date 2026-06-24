package com.swimpulse.pool;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolAddRequestRepository extends JpaRepository<PoolAddRequest, Long> {
	List<PoolAddRequest> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

	List<PoolAddRequest> findByStatusOrderByCreatedAtDescIdDesc(PoolAddRequestStatus status, Pageable pageable);

	long countByStatus(PoolAddRequestStatus status);
}
