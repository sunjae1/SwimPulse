package com.swimpulse.pool;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolRepository extends JpaRepository<Pool, Long> {
	List<Pool> findAllByOrderByNameAsc();

	Optional<Pool> findByName(String name);

	List<Pool> findTop50ByGeocodeStatusOrderByIdAsc(GeocodeStatus geocodeStatus);
}
