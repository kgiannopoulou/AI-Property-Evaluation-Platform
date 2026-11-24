package com.propertyapp.subscriptions;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The freemium plans. These are pricing HYPOTHESES to test, not final prices; this class is the
 * single source of truth (the database's {@code plans} rows mirror it and a test checks they agree).
 */
public final class PlanCatalog {

	private PlanCatalog() {}

	public enum Feature {
		VALUATION("Valuation (fair value range, scores, data quality)"),
		BASIC_INSPECTION("AI inspection of up to 6 photos"),
		FINANCIAL("Purchase and ownership cost calculator"),
		COMPARISON("Property comparison"),
		ADVANCED_VALUATION("Comparable evidence, market comparator and negotiation"),
		FULL_INSPECTION("AI inspection of up to 24 photos"),
		REPORTS("PDF reports"),
		LOCATION("Location intelligence"),
		RENOVATION("Renovation planner"),
		MARKET("Market comparator"),
		BUILDING("Building analysis"),
		DOCUMENTS("Document checker"),
		FLOOR_PLANS("Floor plan reader"),
		NEGOTIATION("Negotiation assistant"),
		INVESTMENT("Investment tools"),
		CLIENT_REPORTS("Client-branded reports"),
		TEAMS("Teams with a shared allowance"),
		BULK_ANALYSIS("Bulk analysis"),
		API_ACCESS("API access with API keys");

		private final String label;

		Feature(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		/** Kebab-case id used in the API and the database ({@code full-inspection}). */
		public String id() {
			return name().toLowerCase(Locale.ROOT).replace('_', '-');
		}
	}

	/**
	 * @param monthlyPriceCents null for custom (contract) pricing
	 * @param analysesPerMonth null = set per contract (Business)
	 */
	public record Plan(
			String id, String name, Integer monthlyPriceCents, Integer analysesPerMonth, int maxInspectionPhotos,
			int maxCompare, Set<Feature> features, List<String> highlights, boolean selfServe) {

		public boolean has(Feature f) {
			return features.contains(f);
		}
	}

	private static final Set<Feature> FREE = java.util.Collections.unmodifiableSet(
			EnumSet.of(Feature.VALUATION, Feature.BASIC_INSPECTION, Feature.FINANCIAL, Feature.COMPARISON));
	private static final Set<Feature> PREMIUM = union(FREE, EnumSet.of(Feature.ADVANCED_VALUATION, Feature.FULL_INSPECTION,
			Feature.REPORTS, Feature.LOCATION, Feature.RENOVATION, Feature.MARKET, Feature.BUILDING, Feature.DOCUMENTS,
			Feature.FLOOR_PLANS, Feature.NEGOTIATION));
	private static final Set<Feature> PROFESSIONAL = union(PREMIUM, EnumSet.of(Feature.INVESTMENT, Feature.CLIENT_REPORTS));
	private static final Set<Feature> BUSINESS = union(PROFESSIONAL, EnumSet.of(Feature.TEAMS, Feature.BULK_ANALYSIS, Feature.API_ACCESS));

	/** Default monthly analyses for a Business contract that does not set its own. */
	public static final int BUSINESS_DEFAULT_ANALYSES = 1000;
	public static final int BUSINESS_DEFAULT_SEATS = 5;

	public static final List<Plan> PLANS = List.of(
			new Plan("free", "Free", 0, 2, 6, 2, FREE,
					List.of("2 analyses a month", "Valuation range and scores", "Basic AI inspection (6 photos)", "Cost calculator"), true),
			new Plan("premium", "Premium", 999, 20, 24, 4, PREMIUM,
					List.of("20 analyses a month", "Advanced valuation with comparable evidence", "PDF reports",
							"Location, market, building, documents, floor plans", "Negotiation assistant"), true),
			new Plan("professional", "Professional", 2999, 100, 24, 6, PROFESSIONAL,
					List.of("100 analyses a month", "Everything in Premium", "Investment tools", "Client-branded reports"), true),
			new Plan("business", "Business", null, null, 24, 6, BUSINESS,
					List.of("Analyses set by contract", "Teams with a shared allowance", "Bulk analysis", "API access"), false));

	public static Plan get(String id) {
		return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + id));
	}

	public static Optional<Plan> find(String id) {
		return PLANS.stream().filter(p -> p.id().equals(id)).findFirst();
	}

	public static Plan free() {
		return PLANS.getFirst();
	}

	/** The cheapest plan that includes a feature, for "upgrade to …" messages. */
	public static Plan cheapestWith(Feature f) {
		return PLANS.stream().filter(p -> p.has(f)).findFirst().orElseThrow();
	}

	private static Set<Feature> union(Set<Feature> a, Set<Feature> b) {
		EnumSet<Feature> s = EnumSet.copyOf(a);
		s.addAll(b);
		return java.util.Collections.unmodifiableSet(s);
	}
}
