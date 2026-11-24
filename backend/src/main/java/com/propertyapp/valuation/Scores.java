package com.propertyapp.valuation;

import com.propertyapp.valuation.MarketDataSource.LocationEvidence;
import com.propertyapp.valuation.ValuationReport.Concern;
import com.propertyapp.valuation.ValuationReport.Score;
import com.propertyapp.valuation.ValuationReport.ScoreItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Condition and location scores, each with the items that produced it. */
public final class Scores {

	private Scores() {}

	static final Map<String, Integer> CONDITION_BASE = Map.of(
			"needs-renovation", 35, "fair", 58, "good", 76, "excellent", 92);

	/** Points off per concern; potential concerns count half. */
	static final Map<String, Double> CONCERN_POINTS = Map.of(
			"info", 0.0, "low", 2.0, "medium", 5.0, "high", 10.0, "critical", 15.0);

	static String normaliseSeverity(String severity) {
		return switch (severity) {
			case "minor" -> "low";
			case "moderate" -> "medium";
			case "major" -> "high";
			default -> severity;
		};
	}

	static Score condition(ValuationRequest.Subject s, List<Concern> concerns, int currentYear) {
		List<ScoreItem> items = new ArrayList<>();
		double score = CONDITION_BASE.get(s.condition());
		items.add(new ScoreItem("Declared condition", score, Adjustments.label(s.condition())));

		int effective = Adjustments.effectiveYear(s.yearBuilt(), s.renovatedYear());
		int age = Math.max(0, currentYear - effective);
		if (age > 25) {
			double points = -Math.min((age - 25) * 0.3, 15);
			score += points;
			items.add(new ScoreItem("Building age", round1(points), s.renovatedYear() != null
					? String.format(Locale.UK, "Renovated %d, %d years ago", s.renovatedYear(), age)
					: String.format(Locale.UK, "Built %d, %d years ago, no recorded renovation", s.yearBuilt(), age)));
		}

		double energy = switch (s.energyClass()) {
			case "A+", "A" -> 4;
			case "B+", "B" -> 2;
			case "C" -> 0;
			case "D" -> -2;
			case "E" -> -4;
			default -> -6;
		};
		if (energy != 0) {
			score += energy;
			items.add(new ScoreItem("Energy class", energy, "Class " + s.energyClass()));
		}

		double deduction = 0;
		for (Concern c : concerns) {
			if (c.source().equals(ValuationEngine.ENGINE_SOURCE)) continue; // age and energy are scored above
			double points = CONCERN_POINTS.getOrDefault(c.severity(), 0.0) * (c.observed() ? 1 : 0.5);
			if (points == 0) continue;
			double applied = Math.min(points, 40 - deduction);
			if (applied <= 0) break;
			deduction += applied;
			items.add(new ScoreItem(c.observed() ? "Observed issue" : "Potential concern", -applied,
					c.title() + " (" + c.severity() + ")"));
		}
		score -= deduction;
		return new Score((int) Math.round(Math.clamp(score, 5, 98)), "declared condition, building age, energy class and inspection findings", items);
	}

	private record Group(String label, double points, Set<String> categories, double idealM) {}

	private static final List<Group> GROUPS = List.of(
			new Group("Rail or metro", 25, Set.of("metro", "train", "tram"), 400),
			new Group("Groceries", 15, Set.of("supermarket", "bakery"), 300),
			new Group("Pharmacy", 10, Set.of("pharmacy"), 300),
			new Group("Hospital or clinic", 5, Set.of("hospital", "clinic"), 600),
			new Group("Schools", 15, Set.of("school", "kindergarten"), 400),
			new Group("Parks and playgrounds", 15, Set.of("park", "playground"), 400));
	private static final double BUS_POINTS = 12;
	private static final Set<String> AMENITIES = Set.of("cafe", "restaurant", "bank", "post-office", "gym", "bakery");
	private static final double AMENITY_POINTS = 15;
	private static final double RADIUS_M = 1000;

	public static Score location(Optional<LocationEvidence> evidence) {
		if (evidence.isEmpty()) {
			return new Score(null, "No location data is available for this address", List.of());
		}
		LocationEvidence e = evidence.get();
		if (e.facilities().isEmpty()) {
			if (e.districtScore() == null) return new Score(null, "No location data is available for this address", List.of());
			return new Score(e.districtScore(), "District reference only (" + e.districtSource() + ")", List.of(
					new ScoreItem("District", e.districtScore(), e.districtName() + ": district average, not this street")));
		}

		List<ScoreItem> items = new ArrayList<>();
		double total = 0;
		for (Group g : GROUPS) {
			Optional<Double> nearest = e.facilities().stream()
					.filter(f -> g.categories().contains(f.category()))
					.map(LocationEvidence.Facility::nearestM)
					.min(Double::compare);
			double points = 0;
			String detail = "None within 1 km";
			if (nearest.isPresent()) {
				points = g.points() * distanceFactor(nearest.get(), g.idealM());
				detail = String.format(Locale.UK, "Nearest %.0f m", nearest.get());
			}
			if (g.label().equals("Rail or metro") && points < BUS_POINTS) {
				Optional<Double> bus = e.facilities().stream()
						.filter(f -> f.category().equals("bus-stop"))
						.map(LocationEvidence.Facility::nearestM)
						.min(Double::compare);
				if (bus.isPresent()) {
					double busPoints = BUS_POINTS * distanceFactor(bus.get(), 300);
					if (busPoints > points) {
						points = busPoints;
						detail = String.format(Locale.UK, "No rail nearby; bus stop %.0f m", bus.get());
					}
				}
			}
			total += points;
			items.add(new ScoreItem(g.label(), round1(points), detail));
		}
		long amenities = e.facilities().stream()
				.filter(f -> AMENITIES.contains(f.category()))
				.mapToLong(LocationEvidence.Facility::places)
				.sum();
		double amenityPoints = AMENITY_POINTS * Math.min(amenities / 8.0, 1);
		total += amenityPoints;
		items.add(new ScoreItem("Shops, cafés and services", round1(amenityPoints), amenities + " within 1 km"));

		String basis = "Facilities within 1 km";
		if (e.districtScore() != null) basis += "; district reference " + e.districtScore() + " (" + e.districtSource() + ") shown for context";
		return new Score((int) Math.round(Math.clamp(total, 0, 100)), basis, items);
	}

	/** 1 up to the ideal distance, falling linearly to 0 at the search radius. */
	private static double distanceFactor(double distanceM, double idealM) {
		if (distanceM <= idealM) return 1;
		return Math.max(0, 1 - (distanceM - idealM) / (RADIUS_M - idealM));
	}

	static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
