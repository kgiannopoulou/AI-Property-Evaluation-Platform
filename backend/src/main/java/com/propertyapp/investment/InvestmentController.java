package com.propertyapp.investment;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Investment Calculator: rental yield, ROI, vacancy and cash flow. */
@RestController
class InvestmentController {

	@PostMapping("/api/v1/investment")
	InvestmentCalculator.Result handle(@Valid @RequestBody InvestmentCalculator.Request request) {
		return InvestmentCalculator.calculate(request);
	}
}
