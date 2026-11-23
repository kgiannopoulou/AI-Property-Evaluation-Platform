package com.propertyapp.common;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal endpoint that proves the service is wired up and lists its modules.
 */
@RestController
@RequestMapping("/api/v1")
public class StatusController {

	static final List<String> MODULES = List.of(
			"users", "properties", "valuation", "financial", "renovation", "location", "subscriptions", "reports",
			"inspection");

	public record Status(String service, String phase, List<String> modules) {}

	@GetMapping("/status")
	public Status status() {
		return new Status("property-backend", "scaffold", MODULES);
	}
}
