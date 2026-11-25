package com.propertyapp.reports;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.propertyapp.common.Database;
import com.propertyapp.financial.FinancialCalculator;
import com.propertyapp.investment.InvestmentCalculator;
import com.propertyapp.location.LocationIntelligence;
import com.propertyapp.negotiation.NegotiationAssistant;
import com.propertyapp.negotiation.NegotiationAssistantTest;
import com.propertyapp.renovation.RenovationPlanner;
import com.propertyapp.valuation.MarketDataSource;
import com.propertyapp.valuation.ValuationEngine;
import com.propertyapp.valuation.ValuationRequest;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReportServiceTest {

	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);

	static ReportService service(MarketDataSource market) {
		return new ReportService(new NegotiationAssistant(new ValuationEngine(market, CLOCK), CLOCK),
				new LocationIntelligence(Database.none()), CLOCK);
	}

	static ReportService.Request request() {
		var subject = NegotiationAssistantTest.subject();
		return new ReportService.Request("Διαμέρισμα 70 τ.μ., Παγκράτι", "Υμηττού 10",
				new ValuationRequest(subject, null, null, null),
				new FinancialCalculator.Request(220_000, "transfer-tax", null, false, null,
						new FinancialCalculator.Mortgage(null, 0.8, 4, 25), null),
				new InvestmentCalculator.Request(220_000, 18_000, 0, 950, null, 50, 300, 150, null, null, null, 0, 0, 0, null, null, null, null),
				new RenovationPlanner.Request(70, 1, 1978, "fair", null, null, null),
				null, null, true, new ReportService.Client("Maria K.", "A. Agent", "Demo Realty", "R-12"));
	}

	static String text(byte[] pdf) throws IOException {
		var reader = new PdfReader(pdf);
		var extractor = new PdfTextExtractor(reader);
		StringBuilder sb = new StringBuilder();
		for (int p = 1; p <= reader.getNumberOfPages(); p++) sb.append(extractor.getTextFromPage(p)).append('\n');
		return sb.toString();
	}

	@Test
	void pdfWithEverySectionRequestedAndGreekText() throws IOException {
		byte[] pdf = service(NegotiationAssistantTest.sales()).pdf(request());
		assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
		String text = text(pdf);
		assertThat(text).contains("Διαμέρισμα 70 τ.μ., Παγκράτι", "Estimated fair market value", "Suggested negotiation strategy",
				"Evidence behind the maximum offer", "Purchase and ownership costs", "Investment", "Renovation scenarios",
				"Method and limitations", "Prepared for Maria K. by A. Agent, Demo Realty · Ref. R-12");
		assertThat(text).doesNotContain("Not enough reliable data");
		assertThat(text.replaceAll("\\s+", " ")).contains("Automated estimate, not a professional appraisal",
				"Estimated fair market value (automated estimate)", "a valuation by a certified valuer");
	}

	@Test
	void renovationValueUsesTheValuationMidpoint() {
		var contents = service(NegotiationAssistantTest.sales()).assemble(request());
		assertThat(contents.renovation().scenarios().get(1).valueAfter()).isNotNull();
	}

	@Test
	void insufficientDataIsStatedInThePdf() throws IOException {
		String text = text(service(MarketDataSource.none()).pdf(request()));
		assertThat(text).contains("Not enough reliable data", "No valuation is shown because the evidence is not good enough");
		assertThat(text).doesNotContain("Evidence behind the maximum offer");
	}
}
