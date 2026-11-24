package com.propertyapp.valuation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Where comparable records and location facts come from (the PostGIS database in production). */
public interface MarketDataSource {

	/** {@code false} when no market database is configured; the engine then says so. */
	boolean connected();

	/** Candidate comparables: same property type and listing family, broadly similar size, recent. */
	List<MarketRecord> comparables(Search search);

	/** Facilities near the subject, or the district's reference score; empty when nothing is known. */
	Optional<LocationEvidence> location(ValuationRequest.Subject subject);

	/**
	 * @param listingTypes sold + sale-listing for a purchase, rented + rent-listing for a rental
	 * @param location {@code null} to match by district name instead of distance
	 */
	record Search(
			ValuationRequest.GeoPoint location,
			String district,
			String propertyType,
			List<String> listingTypes,
			double sizeSqm,
			double sizeTolerance,
			double radiusM,
			LocalDate since,
			int limit) {}

	/**
	 * @param facilities category → (count within the radius, nearest in metres); empty if unknown
	 * @param districtScore a 0-100 reference score for the district, with its source
	 */
	record LocationEvidence(
			List<Facility> facilities, Integer districtScore, String districtName, String districtSource) {

		public record Facility(String category, long places, double nearestM) {}
	}

	/** No database configured: every valuation reports that market data is unavailable. */
	static MarketDataSource none() {
		return new MarketDataSource() {
			@Override
			public boolean connected() {
				return false;
			}

			@Override
			public List<MarketRecord> comparables(Search search) {
				return List.of();
			}

			@Override
			public Optional<LocationEvidence> location(ValuationRequest.Subject subject) {
				return Optional.empty();
			}
		};
	}
}
