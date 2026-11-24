package com.propertyapp.valuation;

import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads comparables and location facts through the V6 PostGIS search functions. */
class JdbcMarketDataSource implements MarketDataSource {

	private static final String SEARCH = """
			SELECT * FROM search_comparables(
			    CASE WHEN ?::double precision IS NULL THEN NULL ELSE geo_point(?::double precision, ?::double precision) END,
			    ?::text, ?::text, ?::text[], ?::numeric, ?::numeric, ?::double precision, ?::date, ?::integer)
			""";

	private final JdbcTemplate jdbc;

	JdbcMarketDataSource(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean connected() {
		return true;
	}

	@Override
	public List<MarketRecord> comparables(Search s) {
		Double lon = s.location() == null ? null : s.location().lon();
		Double lat = s.location() == null ? null : s.location().lat();
		return jdbc.execute((Connection connection) -> {
			Array types = connection.createArrayOf("text", s.listingTypes().toArray());
			try (var statement = connection.prepareStatement(SEARCH)) {
				setNullableDouble(statement, 1, lon);
				setNullableDouble(statement, 2, lon);
				setNullableDouble(statement, 3, lat);
				statement.setString(4, s.district());
				statement.setString(5, s.propertyType());
				statement.setArray(6, types);
				statement.setDouble(7, s.sizeSqm());
				statement.setDouble(8, s.sizeTolerance());
				statement.setDouble(9, s.radiusM());
				statement.setDate(10, Date.valueOf(s.since()));
				statement.setInt(11, s.limit());
				try (ResultSet rs = statement.executeQuery()) {
					var records = new ArrayList<MarketRecord>();
					while (rs.next()) records.add(map(rs));
					return records;
				}
			}
		});
	}

	private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws SQLException {
		if (value == null) statement.setNull(index, Types.DOUBLE);
		else statement.setDouble(index, value);
	}

	private static MarketRecord map(ResultSet rs) throws SQLException {
		return new MarketRecord(
				rs.getString("comparable_id"),
				rs.getString("source"),
				MarketRecord.Kind.fromListingType(rs.getString("listing_type")),
				rs.getString("address"),
				rs.getString("property_type"),
				rs.getDouble("size_sqm"),
				rs.getObject("bedrooms", Integer.class),
				rs.getObject("bathrooms", Integer.class),
				rs.getObject("floor", Integer.class),
				rs.getObject("elevator", Boolean.class),
				rs.getObject("parking", Boolean.class),
				rs.getObject("year_built", Integer.class),
				rs.getObject("renovated_year", Integer.class),
				rs.getString("condition"),
				rs.getString("energy_class"),
				rs.getDouble("price"),
				rs.getDate("observed_on").toLocalDate(),
				rs.getObject("distance_m", Double.class),
				rs.getBoolean("verified"));
	}

	@Override
	public Optional<LocationEvidence> location(ValuationRequest.Subject subject) {
		List<LocationEvidence.Facility> facilities = subject.location() == null
				? List.of()
				: jdbc.query(
						"SELECT category, places, nearest_m FROM location_summary_at(geo_point(?::double precision, ?::double precision), 1000)",
						(rs, i) -> new LocationEvidence.Facility(
								rs.getString("category"), rs.getLong("places"), rs.getDouble("nearest_m")),
						subject.location().lon(), subject.location().lat());

		record District(String name, Integer score, String source) {}
		List<District> districts = subject.location() != null
				? jdbc.query("""
						SELECT name, location_score, source FROM districts
						WHERE boundary IS NOT NULL AND ST_Covers(boundary, geo_point(?::double precision, ?::double precision)) AND location_score IS NOT NULL
						ORDER BY ST_Area(boundary) LIMIT 1
						""",
						(rs, i) -> new District(rs.getString(1), rs.getObject(2, Integer.class), rs.getString(3)),
						subject.location().lon(), subject.location().lat())
				: List.of();
		if (districts.isEmpty() && subject.district() != null && !subject.district().isBlank()) {
			districts = jdbc.query(
					"SELECT name, location_score, source FROM districts WHERE lower(name) = lower(?) AND location_score IS NOT NULL",
					(rs, i) -> new District(rs.getString(1), rs.getObject(2, Integer.class), rs.getString(3)),
					subject.district());
		}
		District district = districts.isEmpty() ? null : districts.getFirst();
		if (facilities.isEmpty() && district == null) return Optional.empty();
		return Optional.of(new LocationEvidence(
				facilities,
				district == null ? null : district.score(),
				district == null ? null : district.name(),
				district == null ? null : district.source()));
	}
}
