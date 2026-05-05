package com.swimpulse.pool;

import com.swimpulse.event.EventResponse;
import com.swimpulse.event.EventService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pools")
public class PoolController {
	private final PoolService poolService;
	private final EventService eventService;

	public PoolController(PoolService poolService, EventService eventService) {
		this.poolService = poolService;
		this.eventService = eventService;
	}

	@GetMapping
	public List<PoolResponse> findPools() {
		return poolService.findPools();
	}

	@GetMapping("/{poolId}")
	public PoolResponse findPool(@PathVariable Long poolId) {
		return poolService.findPool(poolId);
	}

	@GetMapping("/{poolId}/events")
	public List<EventResponse> findPoolEvents(@PathVariable Long poolId) {
		return eventService.findEvents(null, poolId);
	}
}
