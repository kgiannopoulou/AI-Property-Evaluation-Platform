package com.propertyapp.financial;

import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Financial Calculator: taxes, fees, mortgage and ownership costs. */
@RestController
class FinancialController {

	@RequiresFeature(Feature.FINANCIAL)
	@PostMapping("/api/v1/financial")
	FinancialCalculator.Result handle(@Valid @RequestBody FinancialCalculator.Request request) {
		return FinancialCalculator.calculate(request);
	}
}
