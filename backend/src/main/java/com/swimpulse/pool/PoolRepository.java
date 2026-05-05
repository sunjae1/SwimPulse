package com.swimpulse.pool;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolRepository extends JpaRepository<Pool, Long> {
	List<Pool> findAllByOrderByNameAsc();
}
