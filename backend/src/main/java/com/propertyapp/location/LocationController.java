package com.propertyapp.location;

import com.propertyapp.common.Database;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Location Intelligence: nearby services, transport, parks, schools and environmental risks. */
@RestController
class LocationController {

	private final LocationIntelligence location;

	LocationController(LocationIntelligence location) {
		this.location = location;
	}

	@PostMapping("/api/v1/location")
	LocationIntelligence.Result handle(@Valid @RequestBody LocationIntelligence.Request request) {
		return location.analyse(request);
	}

	@Configuration
	static class Config {
		@Bean
		LocationIntelligence locationIntelligence(Database database) {
			return new LocationIntelligence(database);
		}
	}
}
