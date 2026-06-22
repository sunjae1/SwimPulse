package com.swimpulse.pool;

import com.swimpulse.event.EventResponse;
import com.swimpulse.event.EventService;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
	private static final Logger log = LoggerFactory.getLogger(PoolController.class);

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
		log.info("Pool list requested.");
		return poolService.findPools();
	}

	@GetMapping("/{poolId}")
	public PoolResponse findPool(@PathVariable Long poolId) {
		log.info("Pool detail requested. poolId={}", poolId);
		return poolService.findPool(poolId);
	}

	@GetMapping("/nearby")
	public List<NearbyPoolResponse> findNearbyPools(
			@RequestParam(required = false) Double latitude,
			@RequestParam(required = false) Double longitude,
			@RequestParam(required = false) Integer limit
	) {
		log.info("Nearby pools requested. latitude={} longitude={} limit={}", latitude, longitude, limit);
		return poolService.findNearbyPools(latitude, longitude, limit);
	}

	@GetMapping("/location-candidates")
	public List<PoolLocationCandidateResponse> findLocationCandidates(
			@RequestParam Double latitude,
			@RequestParam Double longitude,
			@RequestParam(required = false) Integer radius,
			@RequestParam(required = false) String query,
			@RequestParam(required = false) Integer display
	) {
		log.info("Pool location candidates requested. latitude={} longitude={} radius={} query={} display={}",
				latitude, longitude, radius, query, display);
		return poolService.findLocationCandidates(latitude, longitude, radius, query, display);
	}

	@GetMapping("/{poolId}/events")
	public List<EventResponse> findPoolEvents(@PathVariable Long poolId) {
		log.info("Pool events requested. poolId={}", poolId);
		return eventService.findEvents(null, poolId);
	}

	@PostMapping("/geocode")
	public GeocodeBatchResponse geocodePendingPools() {
		log.info("Pending pool geocode requested.");
		return poolGeocodingService.geocodePendingPools();
	}

	@PostMapping("/from-location-candidate")
	@ResponseStatus(HttpStatus.CREATED)
	public PoolResponse createFromLocationCandidate(
			@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreatePoolFromLocationCandidateRequest request
	) {
		log.info("Pool creation from location candidate requested. userId={} title={}",
				user.id(), request.title());
		return poolService.createFromLocationCandidate(request);
	}

	@PostMapping("/homepages/enrich")
	public HomepageEnrichmentResponse enrichHomepages(@RequestParam(required = false) Integer limit) {
		log.info("Pool homepage enrichment requested. limit={}", limit);
		return poolService.enrichHomepages(limit);
	}

	@PostMapping("/homepages/reverify")
	public HomepageEnrichmentResponse reverifyHomepages(@RequestParam(required = false) Integer limit) {
		log.info("Pool homepage reverification requested. limit={}", limit);
		return poolService.reverifyHomepages(limit);
	}

	@PostMapping("/images/enrich")
	public PoolImageEnrichmentResponse enrichPoolImages(@RequestParam(required = false) Integer limit) {
		log.info("Pool image enrichment requested. limit={}", limit);
		return poolService.enrichPoolImages(limit);
	}

	@PostMapping("/{poolId}/image/enrich")
	public PoolImageEnrichmentResult enrichPoolImage(@PathVariable Long poolId) {
		log.info("Pool image enrichment requested. poolId={}", poolId);
		return poolService.enrichPoolImage(poolId);
	}

	@PostMapping("/images/favicon-enrich")
	public PoolImageEnrichmentResponse enrichPoolFavicons(@RequestParam(required = false) Integer limit) {
		log.info("Pool favicon/default image enrichment requested. limit={}", limit);
		return poolService.enrichPoolFavicons(limit);
	}

	@PostMapping("/{poolId}/favicon/enrich")
	public PoolImageEnrichmentResult enrichPoolFavicon(@PathVariable Long poolId) {
		log.info("Pool favicon/default image enrichment requested. poolId={}", poolId);
		return poolService.enrichPoolFavicon(poolId);
	}
}
