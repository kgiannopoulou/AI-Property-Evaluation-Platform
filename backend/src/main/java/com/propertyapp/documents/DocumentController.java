package com.propertyapp.documents;

import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Document Checker: missing, expired, inconsistent or questionable documents. */
@RestController
class DocumentController {

	private final Clock clock;

	DocumentController(Clock clock) {
		this.clock = clock;
	}

	@RequiresFeature(Feature.DOCUMENTS)
	@PostMapping("/api/v1/documents/check")
	DocumentChecker.Result handle(@Valid @RequestBody DocumentChecker.Request request) {
		return DocumentChecker.check(request, LocalDate.now(clock));
	}
}
