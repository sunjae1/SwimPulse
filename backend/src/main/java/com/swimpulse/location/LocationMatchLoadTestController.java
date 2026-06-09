package com.swimpulse.location;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/loadtest/location-match")
@ConditionalOnProperty(name = "swimpulse.loadtest.enabled", havingValue = "true")
public class LocationMatchLoadTestController {
	private final LocationMatchLoadTestService service;

	public LocationMatchLoadTestController(LocationMatchLoadTestService service) {
		this.service = service;
	}

	@GetMapping
	public LocationMatchLoadTestResponse run(@RequestParam String strategy) {
		return service.run(strategy);
	}
}
