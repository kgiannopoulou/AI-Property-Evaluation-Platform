package com.propertyapp.renovation;

import com.propertyapp.valuation.Adjustments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renovation Planner: basic, medium and premium scenarios with itemised cost ranges.
 *
 * <p>Costs come from a published table of ILLUSTRATIVE bands (per m² or per unit) that should be
 * replaced by local contractor prices; a verified quote for a category replaces the band. Value
 * after works uses the valuation engine's condition ladder, so a renovation is never credited
 * with more uplift than the engine would give the better condition; with no current value, no
 * uplift is claimed at all.
 */
public final class RenovationPlanner {

	private RenovationPlanner() {}

	/** Illustrative cost bands, EUR. {@code perSqm} items scale with the size, the rest are per unit. */
	public record Band(String category, String label, double low, double high, boolean perSqm, double weeks) {}

	static final Map<String, Band> BANDS = bands(
			new Band("paint", "Paint walls and ceilings", 9, 16, true, 1),
			new Band("repairs", "Minor repairs (doors, fittings, sockets)", 900, 2200, false, 0.5),
			new Band("flooring", "New flooring", 35, 70, true, 1.5),
			new Band("doors", "Interior doors", 1800, 4000, false, 0.5),
			new Band("kitchen", "New kitchen (units, worktop, sink, fitting)", 5500, 14000, false, 2),
			new Band("bathroom", "Bathroom refurbishment (per bathroom)", 4500, 9500, false, 2),
			new Band("electrical", "Rewiring and new consumer unit", 45, 75, true, 2),
			new Band("plumbing", "Replace water and drainage pipes", 30, 55, true, 1.5),
			new Band("windows", "Double-glazed windows (≈15 % of floor area)", 55, 95, true, 1),
			new Band("insulation", "Internal insulation of external walls", 30, 60, true, 1.5),
			new Band("heat-pump", "Air-to-water heat pump with radiators", 7500, 13000, false, 1.5),
			new Band("layout", "Layout changes (walls, design, permits)", 4000, 12000, false, 3));

	private static Map<String, Band> bands(Band... list) {
		Map<String, Band> map = new LinkedHashMap<>();
		for (Band b : list) map.put(b.category(), b);
		return Map.copyOf(map);
	}

	record Tier(String id, String label, List<String> categories, String conditionAfter) {}

	static final List<Tier> TIERS = List.of(
			new Tier("basic", "Basic refresh", List.of("paint", "repairs"), null),
			new Tier("medium", "Medium renovation", List.of("paint", "repairs", "flooring", "doors", "kitchen", "bathroom"), "good"),
			new Tier("premium", "Premium renovation",
					List.of("paint", "repairs", "flooring", "doors", "kitchen", "bathroom", "electrical", "plumbing",
							"windows", "insulation", "heat-pump", "layout"),
					"excellent"));

	public record Request(
			@DecimalMin("15") @DecimalMax("2000") double sizeSqm,
			@Min(0) @Max(10) int bathrooms,
			@Min(1850) @Max(2100) int yearBuilt,
			@NotNull @Pattern(regexp = "needs-renovation|fair|good|excellent") String condition,
			/** Current fair value (e.g. the valuation midpoint); without it no value uplift is claimed. */
			@Positive Double currentValue,
			@Size(max = 30) List<@Valid Quote> quotes,
			@Size(max = 60) List<@Valid Concern> concerns) {}

	public record Quote(
			@NotNull @Pattern(regexp = "paint|repairs|flooring|doors|kitchen|bathroom|electrical|plumbing|windows|insulation|heat-pump|layout") String category,
			@Positive double amount,
			@NotBlank @Size(max = 200) String source) {}

	/** A concern from the inspection; its area/title decides which scenario addresses it. */
	public record Concern(@Size(max = 60) String area, @NotBlank @Size(max = 300) String title) {}

	public record Item(String category, String label, double low, double high, String basis) {}

	public record Scenario(
			String tier, String label, List<Item> items, double costLow, double costHigh, double weeks,
			String conditionAfter, Double valueAfter, Double valueUplift, Double netGainMid, List<String> addresses) {}

	public record Result(List<Scenario> scenarios, List<String> notes) {}

	public static Result plan(Request r) {
		Map<String, Quote> quotes = new LinkedHashMap<>();
		for (Quote q : r.quotes() == null ? List.<Quote>of() : r.quotes()) quotes.put(q.category(), q);
		int age = Year.now().getValue() - r.yearBuilt();
		double conditionMultiplier = switch (r.condition()) {
			case "needs-renovation" -> 1.15;
			case "fair" -> 1.0;
			case "good" -> 0.9;
			default -> 0.8;
		};

		List<Scenario> scenarios = new ArrayList<>();
		for (Tier t : TIERS) {
			List<Item> items = new ArrayList<>();
			double weeks = 0;
			for (String category : t.categories()) {
				Band b = BANDS.get(category);
				Quote q = quotes.get(category);
				int units = category.equals("bathroom") ? Math.max(1, r.bathrooms()) : 1;
				double factor = b.perSqm() ? r.sizeSqm() * (category.equals("windows") ? 0.15 : 1) : units;
				if (q != null) {
					items.add(new Item(category, b.label(), q.amount(), q.amount(), "Verified quote: " + q.source()));
				} else {
					items.add(new Item(category, b.label(), round100(b.low() * factor * conditionMultiplier),
							round100(b.high() * factor * conditionMultiplier), String.format(Locale.UK,
									"Illustrative band €%,.0f–%,.0f %s", b.low(), b.high(), b.perSqm() ? "per m²" : units > 1 ? "each" : "")));
				}
				weeks += b.weeks();
			}
			double low = items.stream().mapToDouble(Item::low).sum();
			double high = items.stream().mapToDouble(Item::high).sum();
			// Trades overlap: the programme is shorter than the sum of the items.
			double duration = Math.ceil(weeks * 0.7);

			String after = t.conditionAfter() == null
					? (r.condition().equals("needs-renovation") ? "fair" : r.condition())
					: better(r.condition(), t.conditionAfter());
			Double valueAfter = null;
			Double uplift = null;
			Double net = null;
			if (r.currentValue() != null) {
				double factor = (1 + Adjustments.CONDITION.get(after)) / (1 + Adjustments.CONDITION.get(r.condition()));
				valueAfter = round1000(r.currentValue() * factor);
				uplift = valueAfter - round1000(r.currentValue());
				net = uplift - (low + high) / 2;
			}
			scenarios.add(new Scenario(t.id(), t.label(), List.copyOf(items), low, high, duration, after, valueAfter,
					uplift, net, addresses(t, r.concerns())));
		}

		List<String> notes = new ArrayList<>();
		notes.add("Cost bands are illustrative national figures, not local prices: replace them with contractor quotes (a quote overrides the band).");
		notes.add("Value after works follows the valuation engine's condition adjustment; buyers rarely pay back the full cost of a premium renovation.");
		if (age > 35) notes.add("Building over 35 years old: have wiring and plumbing checked before choosing the basic or medium scenario.");
		if (r.currentValue() == null) notes.add("No current value given, so no value uplift is shown.");
		notes.add("Works in a block of flats may need a small-scale works permit (έγκριση εργασιών μικρής κλίμακας) and the building's consent for shared parts.");
		return new Result(List.copyOf(scenarios), List.copyOf(notes));
	}

	/** Which concerns each scenario deals with, matched on keywords in the concern's area and title. */
	private static List<String> addresses(Tier t, List<Concern> concerns) {
		if (concerns == null) return List.of();
		Map<String, List<String>> keywords = Map.ofEntries(
				Map.entry("paint", List.of("paint", "wall", "stain", "ceiling")),
				Map.entry("flooring", List.of("floor", "tile", "parquet")),
				Map.entry("kitchen", List.of("kitchen")),
				Map.entry("bathroom", List.of("bathroom", "shower", "wc", "toilet")),
				Map.entry("electrical", List.of("electric", "wiring", "socket")),
				Map.entry("plumbing", List.of("plumbing", "pipe", "leak", "water pressure")),
				Map.entry("windows", List.of("window", "glazing")),
				Map.entry("insulation", List.of("insulation", "damp", "mould", "condensation")),
				Map.entry("heat-pump", List.of("heating", "boiler")),
				Map.entry("doors", List.of("door")));
		List<String> out = new ArrayList<>();
		for (Concern c : concerns) {
			String text = ((c.area() == null ? "" : c.area()) + " " + c.title()).toLowerCase(Locale.ROOT);
			for (String category : t.categories()) {
				if (keywords.getOrDefault(category, List.of()).stream().anyMatch(text::contains)) {
					out.add(c.title());
					break;
				}
			}
		}
		return List.copyOf(out);
	}

	private static String better(String current, String target) {
		return Adjustments.CONDITION.get(current) >= Adjustments.CONDITION.get(target) ? current : target;
	}

	private static double round100(double v) {
		return Math.round(v / 100) * 100.0;
	}

	private static double round1000(double v) {
		return Math.round(v / 1000) * 1000.0;
	}
}
