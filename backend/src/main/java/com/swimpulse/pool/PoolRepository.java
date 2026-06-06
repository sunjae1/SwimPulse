package com.swimpulse.pool;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PoolRepository extends JpaRepository<Pool, Long> {
	List<Pool> findAllByOrderByNameAsc();

	Optional<Pool> findByName(String name);

	List<Pool> findTop50ByGeocodeStatusOrderByIdAsc(GeocodeStatus geocodeStatus);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select pool from Pool pool where pool.id = :id")
	Optional<Pool> findByIdForUpdate(Long id);
}
