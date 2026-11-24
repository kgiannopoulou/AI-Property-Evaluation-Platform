package com.propertyapp.valuation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The engine's answer. When {@code status} is {@code insufficient-data} the estimate, verdict and
 * negotiation are {@code null}: the report explains what was searched and why it was not enough,
 * and never shows a figure the evidence cannot support.
 */
public record ValuationReport(
		String status,
		String engine,
		String mode,
		Instant generatedAt,
		double askingPrice,
		double askingPerSqm,
		Estimate estimate,
		String verdict,
		Double deltaPct,
		Score conditionScore,
		Score locationScore,
		DataQuality dataQuality,
		List<ComparableResult> comparables,
		List<Concern> concerns,
		Repairs repairs,
		Negotiation negotiation,
		List<String> methodology,
		String disclaimer) {

	public static final String VALUED = "valued";
	public static final String INSUFFICIENT = "insufficient-data";

	/** Fair market value (sale price, or monthly rent in rent mode), rounded. */
	public record Estimate(double low, double mid, double high, double perSqm, double perSqmLow, double perSqmHigh) {}

	/** A 0-100 score with the items that produced it; {@code score} is null when it cannot be computed. */
	public record Score(Integer score, String basis, List<ScoreItem> items) {}

	public record ScoreItem(String label, double points, String detail) {}

	/**
	 * @param grade high, medium, low or insufficient
	 * @param reasons why the data is insufficient (empty when a valuation was produced)
	 * @param warnings caveats that widen the range or lower the confidence
	 * @param nextSteps what would make a valuation possible (or more reliable)
	 */
	public record DataQuality(
			String grade,
			int score,
			boolean sufficient,
			List<String> reasons,
			List<String> warnings,
			List<String> nextSteps,
			Metrics metrics) {}

	public record Metrics(
			boolean marketDataConnected,
			int candidates,
			int used,
			int excluded,
			int transactions,
			int askingPrices,
			int userSupplied,
			double effectiveSampleSize,
			Double searchRadiusM,
			Double medianDistanceM,
			Double medianAgeMonths,
			Double dispersionPct,
			Double attributeCoveragePct,
			Double averageGrossAdjustmentPct,
			Double monthlyTrendPct) {}

	public record ComparableResult(
			String id,
			String source,
			String listingType,
			String address,
			double sizeSqm,
			double price,
			double pricePerSqm,
			LocalDate observedOn,
			Double distanceM,
			boolean verified,
			List<Adjustment> adjustments,
			Double adjustedPricePerSqm,
			Double grossAdjustmentPct,
			Double weight,
			String excludedReason) {}

	public record Adjustment(String factor, double pct, String detail) {}

	public record Concern(String area, String title, String severity, boolean observed, String source) {}

	/**
	 * @param reflectedInValue how much of the repair need is already priced in by the condition adjustment
	 * @param deductedFromOffer verified costs beyond that, taken off the suggested offers
	 */
	public record Repairs(
			double verifiedLow,
			double verifiedHigh,
			double unverifiedLow,
			double unverifiedHigh,
			double reflectedInValue,
			double deductedFromOffer,
			List<RepairLine> items) {}

	public record RepairLine(String item, double low, double high, String source, boolean verified) {}

	public record Negotiation(
			double openingOffer, double targetPrice, double maxOffer, String strategy, List<String> evidence) {}
}
