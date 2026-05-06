package com.swimpulse.location;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/locations")
public class LocationController {
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
		return locationService.search(query, display, latitude, longitude);
	}

	@GetMapping("/geocode")
	public GeocodedLocationResponse geocode(@RequestParam String address) {
		return locationService.geocode(address);
	}
}
