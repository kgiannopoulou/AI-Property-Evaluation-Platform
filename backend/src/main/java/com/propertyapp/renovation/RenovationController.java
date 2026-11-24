package com.propertyapp.renovation;

import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Renovation Planner: basic, medium and premium scenarios. */
@RestController
class RenovationController {

	@RequiresFeature(Feature.RENOVATION)
	@PostMapping("/api/v1/renovation")
	RenovationPlanner.Result handle(@Valid @RequestBody RenovationPlanner.Request request) {
		return RenovationPlanner.plan(request);
	}
}
