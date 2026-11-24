package com.propertyapp.valuation;

import com.propertyapp.valuation.ValuationReport.Adjustment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a comparable's price per m² into what it would be for the subject property.
 *
 * <p>Each rule is a simple, published percentage so a user can follow the arithmetic. An
 * attribute the comparable's source did not record is skipped (never guessed) and counted
 * against the data quality. These are the engine's starting assumptions; with enough local
 * transactions they should be re-estimated by regression.
 */
final class Adjustments {

	private Adjustments() {}

	/** Value relative to a property in good condition. */
	static final Map<String, Double> CONDITION = Map.of(
			"needs-renovation", -0.15, "fair", -0.06, "good", 0.0, "excellent", 0.07);

	/** Value relative to energy class C. */
	static final Map<String, Double> ENERGY = Map.of(
			"A+", 0.06, "A", 0.05, "B+", 0.03, "B", 0.02, "C", 0.0, "D", -0.02, "E", -0.04, "F", -0.06, "G", -0.08);

	/** Larger homes sell for less per m²: price per m² scales with size^-0.12. */
	static final double SIZE_ELASTICITY = 0.12;
	/** Each year of effective age (since construction or major renovation). */
	static final double AGE_PER_YEAR = 0.0035;
	static final double AGE_CAP = 0.12;
	static final double PARKING = 0.04;
	static final double PER_BATHROOM = 0.025;
	static final double BATHROOM_CAP = 0.05;

	/** Attributes counted for the coverage metric. */
	static final int ATTRIBUTES = 6;

	record Result(List<Adjustment> adjustments, double factor, double gross, int attributesKnown) {}

	static double floorValue(int floor, boolean elevator) {
		if (floor < 0) return -0.12;
		if (floor == 0) return -0.05;
		if (floor == 1) return -0.02;
		if (floor == 2) return 0.0;
		return elevator ? Math.min(floor - 2, 4) * 0.01 : Math.max(-0.03 * (floor - 2), -0.12);
	}

	static int effectiveYear(Integer built, Integer renovated) {
		return renovated != null ? renovated : built;
	}

	/** Relative difference between two ladder values: what the subject is worth per unit of comparable. */
	private static double relative(double subject, double comparable) {
		return (1 + subject) / (1 + comparable) - 1;
	}

	static Result adjust(ValuationRequest.Subject s, MarketRecord c, double askingDiscount) {
		List<Adjustment> list = new ArrayList<>();
		int known = 0;

		if (c.kind() == MarketRecord.Kind.ASKING) {
			list.add(new Adjustment("Asking price", -askingDiscount,
					"Advertised price, not a completed deal: reduced by the typical negotiation margin"));
		}

		double size = Math.pow(c.sizeSqm() / s.sizeSqm(), SIZE_ELASTICITY) - 1;
		if (Math.abs(size) >= 0.002) {
			list.add(new Adjustment("Size", size, String.format(Locale.UK, "%.0f m² versus %.0f m²", c.sizeSqm(), s.sizeSqm())));
		}

		if (c.yearBuilt() != null) {
			known++;
			int subjectYear = effectiveYear(s.yearBuilt(), s.renovatedYear());
			int compYear = effectiveYear(c.yearBuilt(), c.renovatedYear());
			double age = Math.clamp((subjectYear - compYear) * AGE_PER_YEAR, -AGE_CAP, AGE_CAP);
			if (age != 0) {
				list.add(new Adjustment("Building age", age, String.format(Locale.UK,
						"Effective year %d versus %d%s", compYear, subjectYear,
						c.renovatedYear() != null ? " (renovated)" : "")));
			}
		}

		if (c.condition() != null) {
			known++;
			double condition = relative(CONDITION.get(s.condition()), CONDITION.get(c.condition()));
			if (condition != 0) {
				list.add(new Adjustment("Condition", condition,
						String.format(Locale.UK, "%s versus %s", label(c.condition()), label(s.condition()))));
			}
		}

		if (c.floor() != null) {
			known++;
			boolean compElevator = c.elevator() != null ? c.elevator() : s.elevator();
			double floor = relative(floorValue(s.floor(), s.elevator()), floorValue(c.floor(), compElevator));
			if (floor != 0) {
				list.add(new Adjustment("Floor and elevator", floor, String.format(Locale.UK,
						"Floor %d%s versus floor %d%s", c.floor(), elevatorText(c.elevator()),
						s.floor(), elevatorText(s.elevator()))));
			}
		}

		if (c.parking() != null) {
			known++;
			if (c.parking() != s.parking()) {
				double parking = s.parking() ? PARKING : relative(0, PARKING);
				list.add(new Adjustment("Parking", parking,
						s.parking() ? "This property has parking; the comparable does not" : "The comparable has parking; this property does not"));
			}
		}

		if (c.energyClass() != null) {
			known++;
			double energy = relative(ENERGY.get(s.energyClass()), ENERGY.get(c.energyClass()));
			if (energy != 0) {
				list.add(new Adjustment("Energy class", energy,
						String.format(Locale.UK, "Class %s versus %s", c.energyClass(), s.energyClass())));
			}
		}

		if (c.bathrooms() != null) {
			known++;
			double baths = Math.clamp((s.bathrooms() - c.bathrooms()) * PER_BATHROOM, -BATHROOM_CAP, BATHROOM_CAP);
			if (baths != 0) {
				list.add(new Adjustment("Bathrooms", baths,
						String.format(Locale.UK, "%d bathroom(s) versus %d", c.bathrooms(), s.bathrooms())));
			}
		}

		double factor = 1;
		double gross = 0;
		for (Adjustment a : list) {
			factor *= 1 + a.pct();
			gross += Math.abs(a.pct());
		}
		return new Result(List.copyOf(list), factor, gross, known);
	}

	static String label(String condition) {
		return switch (condition) {
			case "needs-renovation" -> "needs renovation";
			default -> condition;
		};
	}

	private static String elevatorText(Boolean elevator) {
		if (elevator == null) return "";
		return elevator ? " with elevator" : " without elevator";
	}
}
