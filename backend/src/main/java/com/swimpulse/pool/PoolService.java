package com.swimpulse.pool;

import com.swimpulse.common.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PoolService {
	private final PoolRepository poolRepository;

	public PoolService(PoolRepository poolRepository) {
		this.poolRepository = poolRepository;
	}

	@Transactional(readOnly = true)
	public List<PoolResponse> findPools() {
		return poolRepository.findAllByOrderByNameAsc()
				.stream()
				.map(PoolResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public PoolResponse findPool(Long poolId) {
		return poolRepository.findById(poolId)
				.map(PoolResponse::from)
				.orElseThrow(() -> new NotFoundException("Pool not found: " + poolId));
	}
}
