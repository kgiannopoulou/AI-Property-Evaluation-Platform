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

	/** Modules with a working API. */
	static final List<String> MODULES = List.of(
			"inspection", "valuation", "location", "financial", "renovation", "market", "investment", "negotiation",
			"building", "documents", "floorplan", "comparison", "reports");

	public record Status(String service, String phase, List<String> modules) {}

	@GetMapping("/status")
	public Status status() {
		return new Status("property-backend", "phase-8", MODULES);
	}
}
