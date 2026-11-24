package com.propertyapp.comparison;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Property Comparison: several properties side by side. */
@RestController
class ComparisonController {

	@PostMapping("/api/v1/comparison")
	PropertyComparison.Result handle(@Valid @RequestBody PropertyComparison.Request request) {
		return PropertyComparison.compare(request);
	}
}
