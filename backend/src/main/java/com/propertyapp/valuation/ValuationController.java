package com.propertyapp.valuation;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/v1/valuations: comparable-sales valuation with data-quality gate. */
@RestController
@RequestMapping("/api/v1/valuations")
public class ValuationController {

	private final ValuationEngine engine;

	public ValuationController(ValuationEngine engine) {
		this.engine = engine;
	}

	@PostMapping
	public ValuationReport value(@Valid @RequestBody ValuationRequest request) {
		return engine.value(request);
	}
}
