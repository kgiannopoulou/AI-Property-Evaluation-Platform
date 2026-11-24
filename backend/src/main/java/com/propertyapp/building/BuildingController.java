package com.propertyapp.building;

import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Building Analysis: elevator, roof, insulation, maintenance and shared costs. */
@RestController
class BuildingController {

	private final Clock clock;

	BuildingController(Clock clock) {
		this.clock = clock;
	}

	@RequiresFeature(Feature.BUILDING)
	@PostMapping("/api/v1/building")
	BuildingAnalysis.Result handle(@Valid @RequestBody BuildingAnalysis.Request request) {
		return BuildingAnalysis.analyse(request, LocalDate.now(clock));
	}
}
