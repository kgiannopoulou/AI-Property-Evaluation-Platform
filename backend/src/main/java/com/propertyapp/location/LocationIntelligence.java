package com.propertyapp.location;

import com.propertyapp.common.Database;
import com.propertyapp.valuation.MarketDataSource.LocationEvidence;
import com.propertyapp.valuation.Scores;
import com.propertyapp.valuation.ValuationReport.Score;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Location Intelligence: nearby services, transport, parks, schools and health care, walking
 * times, the location score (the same scoring the valuation engine uses) and environmental risks.
 *
 * <p>Distances come from PostGIS. Absence of data is never reported as absence of facilities or
 * of risk: a risk type with no layer covering the point is "not assessed".
 */
public class LocationIntelligence {

	/** Walking speed for the minutes shown, metres per minute (about 4.8 km/h). */
	static final double WALK_M_PER_MIN = 80;

	static final Map<String, List<String>> GROUPS = groups();

	private static Map<String, List<String>> groups() {
		Map<String, List<String>> g = new LinkedHashMap<>();
		g.put("Transport", List.of("metro", "train", "tram", "bus-stop"));
		g.put("Schools", List.of("kindergarten", "school", "university"));
		g.put("Parks", List.of("park", "playground"));
		g.put("Health", List.of("pharmacy", "clinic", "hospital"));
		g.put("Services", List.of("supermarket", "bakery", "bank", "post-office", "police", "gym", "cafe", "restaurant", "parking"));
		return g;
	}

	public static final List<String> RISK_TYPES =
			List.of("flood", "seismic", "landslide", "wildfire", "noise", "air-quality", "industrial", "coastal-erosion");

	private final Database database;

	public LocationIntelligence(Database database) {
		this.database = database;
	}

	public record Request(
			@DecimalMin("-90") @DecimalMax("90") double lat,
			@DecimalMin("-180") @DecimalMax("180") double lon,
			@Min(200) @Max(3000) Integer radiusM) {}

	public record Place(String category, String name, double distanceM, int walkMinutes) {}

	public record Group(String group, int count, List<Place> nearest) {}

	/** @param status zone (inside a mapped zone), outside-zones (layer covers the point, no zone) or not-assessed */
	public record Risk(String riskType, String status, String level, String description, String layer, String source, LocalDate publishedOn) {}

	public record Result(boolean dataConnected, double radiusM, List<Group> groups, Score locationScore, List<Risk> risks, List<String> notes) {}

	public Result analyse(Request r) {
		double radius = r.radiusM() == null ? 1500 : r.radiusM();
		List<String> notes = new ArrayList<>();
		if (database.jdbc().isEmpty()) {
			notes.add("No location database is connected, so nearby facilities and risk layers cannot be looked up.");
			List<Risk> risks = RISK_TYPES.stream().map(t -> new Risk(t, "not-assessed", null, null, null, null, null)).toList();
			return new Result(false, radius, List.of(), Scores.location(Optional.empty()), risks, notes);
		}
		JdbcTemplate jdbc = database.jdbc().get();

		List<Place> places = jdbc.query("""
				SELECT category, name, distance_m FROM nearby_places(
				    geo_point(?::double precision, ?::double precision), ?::double precision, NULL, 500)
				""", (rs, i) -> {
			double d = rs.getDouble("distance_m");
			return new Place(rs.getString("category"), rs.getString("name"), d, (int) Math.ceil(d / WALK_M_PER_MIN));
		}, r.lon(), r.lat(), radius);

		List<Group> groups = new ArrayList<>();
		for (var e : GROUPS.entrySet()) {
			List<Place> inGroup = places.stream().filter(p -> e.getValue().contains(p.category())).toList();
			// Nearest of each category first, then the rest, at most 6 per group.
			List<Place> nearest = new ArrayList<>();
			for (String c : e.getValue()) inGroup.stream().filter(p -> p.category().equals(c)).findFirst().ifPresent(nearest::add);
			nearest.sort(java.util.Comparator.comparingDouble(Place::distanceM));
			groups.add(new Group(e.getKey(), inGroup.size(), List.copyOf(nearest.subList(0, Math.min(6, nearest.size())))));
		}
		if (places.isEmpty()) {
			notes.add("No facility data has been imported around this point. That does not mean there are no facilities.");
		}

		List<LocationEvidence.Facility> facilities = jdbc.query(
				"SELECT category, places, nearest_m FROM location_summary_at(geo_point(?::double precision, ?::double precision), 1000)",
				(rs, i) -> new LocationEvidence.Facility(rs.getString(1), rs.getLong(2), rs.getDouble(3)), r.lon(), r.lat());
		Score score = Scores.location(facilities.isEmpty() ? Optional.empty()
				: Optional.of(new LocationEvidence(facilities, null, null, null)));

		record Row(String type, String layer, String source, Date published, boolean covered, String level, String description) {}
		List<Row> rows = jdbc.query("SELECT * FROM environmental_risks_at(geo_point(?::double precision, ?::double precision))",
				(rs, i) -> new Row(rs.getString("risk_type"), rs.getString("layer_name"), rs.getString("source"),
						rs.getDate("published_on"), rs.getBoolean("covered"), rs.getString("level"), rs.getString("description")),
				r.lon(), r.lat());
		List<Risk> risks = new ArrayList<>();
		for (String type : RISK_TYPES) {
			List<Row> forType = rows.stream().filter(x -> x.type().equals(type) && x.covered()).toList();
			if (forType.isEmpty()) {
				risks.add(new Risk(type, "not-assessed", null, null, null, null, null));
				continue;
			}
			Row worst = forType.stream().filter(x -> x.level() != null)
					.max(java.util.Comparator.comparingInt(x -> List.of("low", "medium", "high", "very-high").indexOf(x.level())))
					.orElse(forType.getFirst());
			risks.add(new Risk(type, worst.level() != null ? "zone" : "outside-zones", worst.level(), worst.description(),
					worst.layer(), worst.source(), worst.published() == null ? null : worst.published().toLocalDate()));
		}
		long notAssessed = risks.stream().filter(x -> x.status().equals("not-assessed")).count();
		if (notAssessed > 0) {
			notes.add(notAssessed + " risk type(s) are not assessed: no risk layer covering this point has been imported. Check official maps (e.g. flood risk management plans) before buying.");
		}
		notes.add("Walking times assume 80 m a minute in a straight line; real routes are longer.");
		return new Result(true, radius, List.copyOf(groups), score, List.copyOf(risks), List.copyOf(notes));
	}
}
