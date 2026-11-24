package com.propertyapp.negotiation;

import com.propertyapp.building.BuildingAnalysis;
import com.propertyapp.documents.DocumentChecker;
import com.propertyapp.valuation.ValuationEngine;
import com.propertyapp.valuation.ValuationReport;
import com.propertyapp.valuation.ValuationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Negotiation Assistant: offer suggestions and evidence-based arguments.
 *
 * <p>Price guidance comes only from the valuation engine and only when its data is sufficient.
 * Each argument carries its evidence and a strength: completed sales and verified quotes are
 * strong; asking prices, rule-based building points and unquoted concerns are weaker. Document
 * problems become contract conditions, not price cuts, and legal blockers are walk-away signals.
 */
public class NegotiationAssistant {

	private final ValuationEngine valuation;
	private final Clock clock;

	public NegotiationAssistant(ValuationEngine valuation, Clock clock) {
		this.valuation = valuation;
		this.clock = clock;
	}

	public record Request(
			@NotNull @Valid ValuationRequest valuation,
			@Valid BuildingAnalysis.Request building,
			@Valid DocumentChecker.Request documents) {}

	/** @param strength strong, moderate or weak; source valuation, repairs, building, documents or inspection */
	public record Argument(String point, String evidence, String strength, String source) {}

	public record PriceGuidance(double openingOffer, double targetPrice, double maxOffer, String verdict, Double deltaPct, String dataQuality) {}

	public record Result(
			PriceGuidance price, String priceNote, String strategy, List<Argument> arguments, List<String> conditions,
			List<String> walkAwaySignals, ValuationReport valuation, BuildingAnalysis.Result building,
			DocumentChecker.Result documents) {}

	public Result advise(Request r) {
		LocalDate today = LocalDate.now(clock);
		ValuationReport v = valuation.value(r.valuation());
		BuildingAnalysis.Result b = r.building() == null ? null : BuildingAnalysis.analyse(r.building(), today);
		DocumentChecker.Result d = r.documents() == null ? null : DocumentChecker.check(r.documents(), today);
		boolean rent = r.valuation().subject().listingType().equals("rent");

		List<Argument> args = new ArrayList<>();
		List<String> conditions = new ArrayList<>();
		List<String> walkAway = new ArrayList<>();
		PriceGuidance price = null;
		String priceNote;
		String strategy;

		if (v.status().equals(ValuationReport.VALUED)) {
			var n = v.negotiation();
			price = new PriceGuidance(n.openingOffer(), n.targetPrice(), n.maxOffer(), v.verdict(), v.deltaPct(), v.dataQuality().grade());
			String grade = v.dataQuality().grade();
			String strength = v.dataQuality().metrics().transactions() == 0 ? "weak" : grade.equals("high") ? "strong" : grade.equals("medium") ? "moderate" : "weak";
			if (v.verdict().equals("above-market")) {
				args.add(new Argument(String.format(Locale.UK, "The asking %s is %.1f%% above the estimated fair value.", rent ? "rent" : "price", v.deltaPct()),
						n.evidence().getFirst(), strength, "valuation"));
			}
			n.evidence().stream().filter(e -> e.startsWith("Sold") || e.startsWith("Let") || e.startsWith("Advertised")).limit(3)
					.forEach(e -> args.add(new Argument("Comparable evidence", e, e.startsWith("Advertised") ? "weak" : strength, "valuation")));
			if (v.repairs().deductedFromOffer() > 0) {
				args.add(new Argument(String.format(Locale.UK, "Verified repairs of about €%,.0f are not reflected in the price.", v.repairs().deductedFromOffer()),
						"Written quotes, beyond the discount the condition already carries.", "strong", "repairs"));
			}
			priceNote = "Offers from the valuation engine (" + grade + " data quality).";
			strategy = n.strategy();
			if (v.deltaPct() != null && v.deltaPct() > 15) walkAway.add(String.format(Locale.UK, "The seller will not move far from an asking price %.0f%% above fair value.", v.deltaPct()));
		} else {
			priceNote = "No price guidance: " + String.join(" ", v.dataQuality().reasons());
			strategy = "Do not anchor on a number the evidence cannot support. Ask the agent for recent registered sale prices nearby, "
					+ "and negotiate on the documented issues and terms below.";
			if (!rent) conditions.add("Commission an independent valuation before signing the preliminary agreement.");
		}

		for (ValuationReport.Concern c : v.concerns()) {
			if (c.source().equals("valuation-engine") || c.severity().equals("info") || c.severity().equals("low")) continue;
			args.add(new Argument(c.title(), c.observed() ? "Observed (" + c.source() + "); get a written quote to turn it into a price argument."
					: "Potential concern (" + c.source() + "); needs a survey.", c.observed() ? "moderate" : "weak", "inspection"));
		}
		if (v.concerns().stream().anyMatch(c -> !c.observed() && !c.severity().equals("info"))) {
			conditions.add("Make the offer conditional on a building survey of the potential concerns.");
		}

		if (b != null) {
			for (String p : b.negotiationPoints()) args.add(new Argument(p, "Building analysis (rule-based).", "moderate", "building"));
			if (b.negotiationPoints().stream().anyMatch(p -> p.startsWith("Planned building works"))) {
				conditions.add("Seller pays for building works voted before completion; attach the general assembly minutes.");
			}
		}

		if (d != null) {
			List<String> missing = new ArrayList<>();
			for (DocumentChecker.Finding f : d.findings()) {
				if (f.kind().equals("missing")) {
					missing.add(d.checklist().stream().filter(c -> c.type().equals(f.documentType()))
							.map(DocumentChecker.ChecklistItem::label).findFirst().orElse(f.documentType()));
					if (f.severity().equals("blocker")) walkAway.add(f.message());
				} else if (f.severity().equals("blocker")) {
					walkAway.add(f.message());
					conditions.add("Before signing: " + f.message() + " " + f.recommendation());
				} else if (f.severity().equals("high")) {
					conditions.add(f.message() + " " + f.recommendation());
				}
			}
			if (!missing.isEmpty()) {
				conditions.add(0, "The seller provides before the preliminary agreement: " + String.join("; ", missing) + ".");
			}
			if (d.findings().stream().anyMatch(f -> f.message().startsWith("Encumbrances"))) {
				conditions.add("All mortgages and liens are lifted at or before the final contract, paid from the price if necessary.");
			}
		}
		if (!rent) {
			conditions.add("Pay the deposit (typically 10%) under a preliminary agreement (προσύμφωνο) that returns it if these conditions are not met.");
		} else {
			conditions.add("Agree in the lease which repairs the landlord makes before you move in, and the deposit terms.");
		}

		return new Result(price, priceNote, strategy, List.copyOf(args), List.copyOf(new java.util.LinkedHashSet<>(conditions)),
				List.copyOf(walkAway), v, b, d);
	}
}
