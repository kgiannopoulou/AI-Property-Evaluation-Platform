package com.propertyapp.investment;

import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Investment Calculator: rental yield, ROI, vacancy and cash flow. */
@RestController
class InvestmentController {

	@RequiresFeature(Feature.INVESTMENT)
	@PostMapping("/api/v1/investment")
	InvestmentCalculator.Result handle(@Valid @RequestBody InvestmentCalculator.Request request) {
		return InvestmentCalculator.calculate(request);
	}
}
