package com.swimpulse.location;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/locations")
public class LocationController {
	private static final Logger log = LoggerFactory.getLogger(LocationController.class);

	private final LocationService locationService;

	public LocationController(LocationService locationService) {
		this.locationService = locationService;
	}

	@GetMapping("/search")
	public List<LocationSearchCandidate> search(
			@RequestParam String query,
			@RequestParam(required = false) Integer display,
			@RequestParam(required = false) Double latitude,
			@RequestParam(required = false) Double longitude
	) {
		log.info("Location search requested. query={} display={} hasOrigin={}",
				query, display, latitude != null && longitude != null);
		return locationService.search(query, display, latitude, longitude);
	}

	@GetMapping("/geocode")
	public GeocodedLocationResponse geocode(@RequestParam String address) {
		log.info("Location geocode requested. address={}", address);
		return locationService.geocode(address);
	}

	@GetMapping("/reverse-geocode")
	public GeocodedLocationResponse reverseGeocode(
			@RequestParam Double latitude,
			@RequestParam Double longitude
	) {
		log.info("Location reverse geocode requested. latitude={} longitude={}", latitude, longitude);
		return locationService.reverseGeocode(latitude, longitude);
	}
}
