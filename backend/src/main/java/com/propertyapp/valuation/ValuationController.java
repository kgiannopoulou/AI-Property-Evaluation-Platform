package com.propertyapp.valuation;

import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.Access.Entitlements;
import com.propertyapp.subscriptions.Access.Metered;
import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.AccessInterceptor;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/valuations: comparable-sales valuation with data-quality gate (one analysis).
 * POST /api/v1/bulk/valuations: up to 50 properties in one call (Business; one analysis each).
 */
@RestController
@RequestMapping("/api/v1")
public class ValuationController {

	static final String BASIC_NOTE = "Free plan: the comparable evidence, adjustments and negotiation strategy are part of Premium.";

	private final ValuationEngine engine;
	private final Entitlements entitlements;

	public ValuationController(ValuationEngine engine, Entitlements entitlements) {
		this.engine = engine;
		this.entitlements = entitlements;
	}

	@RequiresFeature(Feature.VALUATION)
	@Metered
	@PostMapping("/valuations")
	public ValuationReport value(Caller caller, @Valid @RequestBody ValuationRequest request) {
		ValuationReport report = engine.value(request);
		return caller.has(Feature.ADVANCED_VALUATION) ? report : basic(report);
	}

	/** The Free view: the range, verdict, scores, concerns and data quality, without the evidence detail. */
	static ValuationReport basic(ValuationReport r) {
		var q = r.dataQuality();
		List<String> warnings = new ArrayList<>(q.warnings());
		warnings.add(BASIC_NOTE);
		var quality = new ValuationReport.DataQuality(q.grade(), q.score(), q.sufficient(), q.reasons(), List.copyOf(warnings),
				q.nextSteps(), q.metrics());
		return new ValuationReport(r.status(), r.engine(), r.mode(), r.generatedAt(), r.askingPrice(), r.askingPerSqm(), r.estimate(),
				r.verdict(), r.deltaPct(), r.conditionScore(), r.locationScore(), quality, List.of(), r.concerns(), r.repairs(), null,
				r.methodology(), r.disclaimer(), r.notice());
	}

	public record BulkItem(@NotNull @Pattern(regexp = "[A-Za-z0-9_.:-]{1,100}") String propertyRef, @NotNull @Valid ValuationRequest request) {}

	public record BulkRequest(@NotNull @Size(min = 1, max = 50) List<@Valid BulkItem> items) {}

	public record BulkResult(String propertyRef, ValuationReport report) {}

	public record BulkResponse(int analysesCounted, int analysesUsed, int analysesLimit, List<BulkResult> results) {}

	@RequiresFeature(Feature.BULK_ANALYSIS)
	@PostMapping("/bulk/valuations")
	public BulkResponse bulk(Caller caller, @Valid @RequestBody BulkRequest request, HttpServletResponse response) {
		List<String> refs = request.items().stream().map(i -> "p:" + i.propertyRef()).toList();
		var usage = entitlements.consume(caller, refs, "/api/v1/bulk/valuations");
		try {
			List<BulkResult> results = request.items().stream().map(i -> new BulkResult(i.propertyRef(), engine.value(i.request()))).toList();
			AccessInterceptor.usageHeaders(response, caller.analysesLimit(), usage.used());
			return new BulkResponse(usage.counted(), usage.used(), caller.analysesLimit(), results);
		} catch (RuntimeException e) {
			entitlements.release(usage.eventIds());
			throw e;
		}
	}
}
