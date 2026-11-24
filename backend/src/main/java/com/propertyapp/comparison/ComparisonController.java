package com.propertyapp.comparison;

import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.Access.UpgradeRequired;
import com.propertyapp.subscriptions.PlanCatalog;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Property Comparison: several properties side by side (2 on Free, 4 on Premium, 6 on Professional). */
@RestController
class ComparisonController {

	@RequiresFeature(Feature.COMPARISON)
	@PostMapping("/api/v1/comparison")
	PropertyComparison.Result handle(Caller caller, @Valid @RequestBody PropertyComparison.Request request) {
		int max = caller.plan().maxCompare();
		if (request.properties().size() > max) {
			String next = PlanCatalog.PLANS.stream().filter(p -> p.maxCompare() >= request.properties().size()).findFirst()
					.map(PlanCatalog.Plan::name).orElse("Professional");
			throw new UpgradeRequired("The " + caller.plan().name() + " plan compares up to " + max + " properties. Upgrade to "
					+ next + " to compare " + request.properties().size() + ".", Feature.COMPARISON, caller.plan());
		}
		return PropertyComparison.compare(request);
	}
}
