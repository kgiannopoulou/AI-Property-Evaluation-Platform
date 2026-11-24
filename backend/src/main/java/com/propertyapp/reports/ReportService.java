package com.propertyapp.reports;

import com.propertyapp.building.BuildingAnalysis;
import com.propertyapp.documents.DocumentChecker;
import com.propertyapp.financial.FinancialCalculator;
import com.propertyapp.investment.InvestmentCalculator;
import com.propertyapp.location.LocationIntelligence;
import com.propertyapp.negotiation.NegotiationAssistant;
import com.propertyapp.renovation.RenovationPlanner;
import com.propertyapp.valuation.ValuationReport;
import com.propertyapp.valuation.ValuationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Reports: runs the requested modules on the given inputs and renders one PDF. The report is built
 * from module inputs rather than finished results, so every figure in it comes from the engines.
 */
public class ReportService {

	private final NegotiationAssistant negotiation;
	private final LocationIntelligence location;
	private final Clock clock;

	public ReportService(NegotiationAssistant negotiation, LocationIntelligence location, Clock clock) {
		this.negotiation = negotiation;
		this.location = location;
		this.clock = clock;
	}

	public record Request(
			@NotBlank @Size(max = 120) String title,
			@Size(max = 200) String address,
			@NotNull @Valid ValuationRequest valuation,
			@Valid FinancialCalculator.Request financial,
			@Valid InvestmentCalculator.Request investment,
			@Valid RenovationPlanner.Request renovation,
			@Valid BuildingAnalysis.Request building,
			@Valid DocumentChecker.Request documents,
			boolean includeLocation) {}

	/** Everything the PDF shows. Sections the caller did not ask for are null. */
	public record Contents(
			String title, String address, LocalDate date, ValuationReport valuation, NegotiationAssistant.Result negotiation,
			FinancialCalculator.Result financial, InvestmentCalculator.Result investment, RenovationPlanner.Result renovation,
			LocationIntelligence.Result location) {}

	public Contents assemble(Request r) {
		NegotiationAssistant.Result n = negotiation.advise(new NegotiationAssistant.Request(r.valuation(), r.building(), r.documents()));
		RenovationPlanner.Request renovation = r.renovation();
		if (renovation != null && renovation.currentValue() == null && n.valuation().estimate() != null) {
			// Value after works is based on the valuation's own midpoint when the caller gives none.
			renovation = new RenovationPlanner.Request(renovation.sizeSqm(), renovation.bathrooms(), renovation.yearBuilt(),
					renovation.condition(), n.valuation().estimate().mid(), renovation.quotes(), renovation.concerns());
		}
		var s = r.valuation().subject();
		LocationIntelligence.Result loc = r.includeLocation() && s.location() != null
				? location.analyse(new LocationIntelligence.Request(s.location().lat(), s.location().lon(), null))
				: null;
		return new Contents(r.title(), r.address(), LocalDate.now(clock), n.valuation(), n,
				r.financial() == null ? null : FinancialCalculator.calculate(r.financial()),
				r.investment() == null ? null : InvestmentCalculator.calculate(r.investment()),
				renovation == null ? null : RenovationPlanner.plan(renovation), loc);
	}

	public byte[] pdf(Request r) {
		return PdfReportWriter.write(assemble(r));
	}
}
