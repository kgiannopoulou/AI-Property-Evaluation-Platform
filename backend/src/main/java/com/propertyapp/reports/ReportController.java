package com.propertyapp.reports;

import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.Access.Metered;
import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import com.propertyapp.location.LocationIntelligence;
import com.propertyapp.negotiation.NegotiationAssistant;
import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Reports: a downloadable PDF built by running the modules on the given inputs. */
@RestController
class ReportController {

	private final ReportService reports;

	ReportController(ReportService reports) {
		this.reports = reports;
	}

	@RequiresFeature(Feature.REPORTS)
	@Metered
	@PostMapping(value = "/api/v1/reports/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> pdf(Caller caller, @Valid @RequestBody ReportService.Request request) {
		checkSections(caller, request);
		byte[] pdf = reports.pdf(request);
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("property-report.pdf").build().toString())
				.body(pdf);
	}

	/** The same contents as JSON, for the website to show before downloading. */
	@RequiresFeature(Feature.REPORTS)
	@Metered
	@PostMapping("/api/v1/reports")
	ReportService.Contents contents(Caller caller, @Valid @RequestBody ReportService.Request request) {
		checkSections(caller, request);
		return reports.assemble(request);
	}

	/** Sections from higher plans: investment tools and client-branded reports (Professional). */
	static void checkSections(Caller caller, ReportService.Request request) {
		if (request.investment() != null) caller.require(Feature.INVESTMENT);
		if (request.client() != null) caller.require(Feature.CLIENT_REPORTS);
	}

	@Configuration
	static class Config {
		@Bean
		ReportService reportService(NegotiationAssistant negotiation, LocationIntelligence location, Clock clock) {
			return new ReportService(negotiation, location, clock);
		}
	}
}
