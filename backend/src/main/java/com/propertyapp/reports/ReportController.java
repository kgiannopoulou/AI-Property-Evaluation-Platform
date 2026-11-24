package com.propertyapp.reports;

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

	@PostMapping(value = "/api/v1/reports/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> pdf(@Valid @RequestBody ReportService.Request request) {
		byte[] pdf = reports.pdf(request);
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("property-report.pdf").build().toString())
				.body(pdf);
	}

	/** The same contents as JSON, for the website to show before downloading. */
	@PostMapping("/api/v1/reports")
	ReportService.Contents contents(@Valid @RequestBody ReportService.Request request) {
		return reports.assemble(request);
	}

	@Configuration
	static class Config {
		@Bean
		ReportService reportService(NegotiationAssistant negotiation, LocationIntelligence location, Clock clock) {
			return new ReportService(negotiation, location, clock);
		}
	}
}
