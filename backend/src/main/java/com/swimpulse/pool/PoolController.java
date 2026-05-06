package com.swimpulse.pool;

import com.swimpulse.event.EventResponse;
import com.swimpulse.event.EventService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.swimpulse.auth.AuthenticatedUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pools")
public class PoolController {
	private final PoolService poolService;
	private final PoolGeocodingService poolGeocodingService;
	private final EventService eventService;

	public PoolController(
			PoolService poolService,
			PoolGeocodingService poolGeocodingService,
			EventService eventService
	) {
		this.poolService = poolService;
		this.poolGeocodingService = poolGeocodingService;
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

	@GetMapping("/nearby")
	public List<NearbyPoolResponse> findNearbyPools(
			@RequestParam(required = false) Double latitude,
			@RequestParam(required = false) Double longitude,
			@RequestParam(required = false) Integer limit
	) {
		return poolService.findNearbyPools(latitude, longitude, limit);
	}

	@GetMapping("/{poolId}/events")
	public List<EventResponse> findPoolEvents(@PathVariable Long poolId) {
		return eventService.findEvents(null, poolId);
	}

	@PostMapping("/geocode")
	public GeocodeBatchResponse geocodePendingPools() {
		return poolGeocodingService.geocodePendingPools();
	}

	@PostMapping("/from-location-candidate")
	@ResponseStatus(HttpStatus.CREATED)
	public PoolResponse createFromLocationCandidate(
			@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreatePoolFromLocationCandidateRequest request
	) {
		return poolService.createFromLocationCandidate(request);
	}

	@PostMapping("/homepages/enrich")
	public HomepageEnrichmentResponse enrichHomepages(@RequestParam(required = false) Integer limit) {
		return poolService.enrichHomepages(limit);
	}
}
