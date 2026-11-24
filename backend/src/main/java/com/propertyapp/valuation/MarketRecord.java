package com.propertyapp.valuation;

import java.time.LocalDate;

/**
 * One piece of market evidence: a registered transaction, or an advertised (asking) price.
 *
 * <p>Attributes that the source did not record are {@code null}; the engine then skips the
 * matching adjustment and counts the gap against the data quality instead of guessing.
 *
 * @param price sale price, or monthly rent for rental records, EUR
 * @param distanceM metres from the subject property; {@code null} when matched by district only
 * @param verified the record comes from a registry or an agent's completion data, not an advert
 */
public record MarketRecord(
		String id,
		String source,
		Kind kind,
		String address,
		String propertyType,
		double sizeSqm,
		Integer bedrooms,
		Integer bathrooms,
		Integer floor,
		Boolean elevator,
		Boolean parking,
		Integer yearBuilt,
		Integer renovatedYear,
		String condition,
		String energyClass,
		double price,
		LocalDate observedOn,
		Double distanceM,
		boolean verified) {

	public enum Kind {
		/** A completed sale or a signed lease. */
		TRANSACTION,
		/** An advertised price; sellers usually accept less. */
		ASKING;

		static Kind fromListingType(String listingType) {
			return switch (listingType) {
				case "sold", "rented" -> TRANSACTION;
				case "sale-listing", "rent-listing" -> ASKING;
				default -> throw new IllegalArgumentException("Unknown listing type: " + listingType);
			};
		}
	}

	public double pricePerSqm() {
		return price / sizeSqm;
	}
}
