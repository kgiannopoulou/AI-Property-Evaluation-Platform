package com.propertyapp.inspection;

import java.math.BigDecimal;
import java.util.List;

/**
 * Structured result of the AI visual inspection, as returned by the ai-service
 * ({@code POST /v1/inspections}). Field names match its JSON.
 */
public record InspectionReport(
		String propertyId,
		String generatedAt,
		String model,
		List<String> servedBy,
		PhotoCounts photos,
		List<Finding> findings,
		List<AreaCondition> areaConditions,
		int discardedFindings,
		String disclaimer) {

	public record PhotoCounts(int submitted, int analysed, List<SkippedPhoto> skipped) {}

	public record SkippedPhoto(String photoId, String reason) {}

	/**
	 * One finding. {@code assessment} is "observed" or "potential-concern"; hidden structural damage,
	 * electrical safety and moisture extent are always potential concerns.
	 */
	public record Finding(
			String id,
			List<String> photoIds,
			String category,
			String categoryLabel,
			String title,
			String description,
			String severity,
			double confidence,
			String assessment,
			String limitation,
			String recommendation,
			CostEstimate estimatedCost,
			String costNote) {}

	/** Repair-cost range from local cost data; {@code basis} is "verified" or "illustrative". */
	public record CostEstimate(
			BigDecimal low,
			BigDecimal high,
			String currency,
			String basis,
			String source,
			String region,
			String asOf,
			String covers) {}

	public record AreaCondition(String area, String condition, String summary) {}
}
