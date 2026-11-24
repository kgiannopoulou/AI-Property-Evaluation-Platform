package com.propertyapp.building;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Building Analysis: elevator, roof, insulation, facade, structure, maintenance and shared costs.
 *
 * <p>Rule-based: each component gets a rating from what is known about it, the issues found and
 * the question to ask the building manager. Unknown items are reported as unknown (and lower the
 * completeness), never assumed good. Shared costs are compared with a typical range for Athens
 * blocks of flats (configurable reference values, not market data).
 */
public final class BuildingAnalysis {

	private BuildingAnalysis() {}

	static final String CONDITION = "poor|fair|good|excellent";

	/** Typical common charges for a block of flats, EUR per m² a month (without / with central heating). */
	static final double CHARGES_TYPICAL_LOW = 0.6;
	static final double CHARGES_TYPICAL_HIGH = 1.4;
	static final double HEATING_ALLOWANCE = 1.0;

	public record Request(
			@Min(1850) @Max(2100) int yearBuilt,
			@Min(1) @Max(100) Integer floorsAboveGround,
			@Min(1) @Max(2000) Integer units,
			@Min(-2) @Max(60) Integer propertyFloor,
			@DecimalMin("15") @DecimalMax("2000") double propertySizeSqm,
			@Pattern(regexp = "reinforced-concrete|masonry|steel|timber|mixed|unknown") String structureType,
			@Min(1950) @Max(2100) Integer seismicUpgradeYear,
			@Pattern(regexp = CONDITION) String structuralCondition,
			@Min(0) @Max(20) int elevatorCount,
			@Min(1900) @Max(2100) Integer elevatorInstalledYear,
			LocalDate elevatorLastInspection,
			@Pattern(regexp = CONDITION) String roofCondition,
			@Min(1900) @Max(2100) Integer roofWaterproofingYear,
			@Pattern(regexp = CONDITION) String facadeCondition,
			Boolean externalInsulation,
			@Pattern(regexp = "single|double|triple|unknown") String windows,
			@Pattern(regexp = CONDITION) String commonAreasCondition,
			@PositiveOrZero Double monthlyCommonCharges,
			Boolean chargesIncludeHeating,
			@PositiveOrZero Double reserveFund,
			@Size(max = 500) String plannedWorks,
			@PositiveOrZero Double plannedLevy,
			Boolean buildingPermit,
			Boolean pilotis) {}

	/** @param rating good, fair, attention, poor or unknown */
	public record Component(String component, String rating, int score, List<String> issues, List<String> ask) {}

	public record SharedCosts(
			Double monthlyCharges, Double perSqm, double typicalLow, double typicalHigh, String assessment,
			Double plannedLevy, Double reserveFund) {}

	public record Result(int score, int completenessPct, List<Component> components, SharedCosts sharedCosts,
			List<String> negotiationPoints, List<String> notes) {}

	public static Result analyse(Request r, LocalDate today) {
		List<Component> components = new ArrayList<>();
		List<String> points = new ArrayList<>();
		int age = today.getYear() - r.yearBuilt();

		// Structure and seismic design ------------------------------------------------------
		List<String> issues = new ArrayList<>();
		List<String> ask = new ArrayList<>();
		int code = r.seismicUpgradeYear() != null ? r.seismicUpgradeYear() : r.yearBuilt();
		int structure = code >= 1995 ? 90 : code >= 1985 ? 75 : code >= 1959 ? 60 : 40;
		if (code < 1959) issues.add("Designed before Greece's first seismic code (1959).");
		else if (code < 1985) issues.add("Designed to the 1959 seismic code, before the 1984-85 revision.");
		else if (code < 1995) issues.add("Designed to the 1985 seismic rules, before the modern code (EAK 1995/2000).");
		if ("masonry".equals(r.structureType())) {
			structure -= 10;
			issues.add("Load-bearing masonry: ask a structural engineer about seismic resilience.");
		}
		if (r.pilotis() != null && r.pilotis() && code < 1985) {
			structure -= 10;
			issues.add("Open ground floor (pilotis) in an older building: a known weak point in earthquakes.");
			ask.add("Has a structural engineer assessed the pilotis columns?");
		}
		structure = adjustForCondition(structure, r.structuralCondition(), issues, "Structure");
		if (r.seismicUpgradeYear() != null) issues.add("Seismic upgrade recorded in " + r.seismicUpgradeYear() + ".");
		if (r.structuralCondition() == null) ask.add("Any visible cracks in columns, beams or the stairwell? Any engineer's report?");
		components.add(component("Structure", structure, r.structuralCondition() == null && r.seismicUpgradeYear() == null, issues, ask));

		// Elevator --------------------------------------------------------------------------
		issues = new ArrayList<>();
		ask = new ArrayList<>();
		if (r.elevatorCount() == 0) {
			int floors = r.floorsAboveGround() == null ? 0 : r.floorsAboveGround();
			int s = floors >= 4 ? 40 : 75;
			if (floors >= 4) issues.add("No elevator in a building of " + floors + " floors.");
			if (r.propertyFloor() != null && r.propertyFloor() >= 3) {
				issues.add("The property is on floor " + r.propertyFloor() + " without an elevator.");
				points.add("No elevator to floor " + r.propertyFloor() + ": a smaller pool of buyers and tenants.");
			}
			components.add(new Component("Elevator", s >= 70 ? "fair" : "attention", s, issues, ask));
		} else {
			int s = 80;
			if (r.elevatorInstalledYear() != null && today.getYear() - r.elevatorInstalledYear() > 25) {
				s -= 15;
				issues.add("Elevator installed " + r.elevatorInstalledYear() + ": modernisation may be due.");
				ask.add("Has the building voted on elevator modernisation?");
			}
			if (r.elevatorLastInspection() == null) {
				s -= 10;
				ask.add("Ask for the elevator's latest periodic inspection certificate.");
			} else if (r.elevatorLastInspection().isBefore(today.minusYears(2))) {
				s -= 20;
				issues.add("Last recorded inspection " + r.elevatorLastInspection() + " is more than two years ago.");
				points.add("Elevator inspection overdue: certification and any required repairs are a shared cost.");
			}
			components.add(component("Elevator", s, r.elevatorInstalledYear() == null && r.elevatorLastInspection() == null, issues, ask));
		}

		// Roof ------------------------------------------------------------------------------
		issues = new ArrayList<>();
		ask = new ArrayList<>();
		int roof = 75;
		if (r.roofWaterproofingYear() != null) {
			int since = today.getYear() - r.roofWaterproofingYear();
			if (since > 15) {
				roof -= 20;
				issues.add("Roof waterproofing is " + since + " years old (typical life 10-20 years).");
			} else {
				issues.add("Roof waterproofed in " + r.roofWaterproofingYear() + ".");
			}
		} else {
			ask.add("When was the roof last waterproofed?");
		}
		roof = adjustForCondition(roof, r.roofCondition(), issues, "Roof");
		boolean topFloor = r.propertyFloor() != null && r.floorsAboveGround() != null && r.propertyFloor() >= r.floorsAboveGround() - 1;
		if (topFloor && roof < 60) points.add("Top-floor flat under a roof in poor repair: ask for the roof to be fixed or the price reduced.");
		components.add(component("Roof", roof, r.roofCondition() == null && r.roofWaterproofingYear() == null, issues, ask));

		// Insulation, windows and facade ------------------------------------------------------
		issues = new ArrayList<>();
		ask = new ArrayList<>();
		int envelope = 60;
		if (Boolean.TRUE.equals(r.externalInsulation())) {
			envelope += 20;
			issues.add("External thermal insulation installed.");
		} else if (Boolean.FALSE.equals(r.externalInsulation())) {
			envelope -= 10;
			issues.add(r.yearBuilt() < 1980 ? "No external insulation (buildings before 1980 were built without it)." : "No external insulation.");
		} else {
			ask.add("Is the facade insulated? Any plan to apply for an energy-upgrade subsidy?");
		}
		if ("single".equals(r.windows())) {
			envelope -= 10;
			issues.add("Single glazing: heat loss, condensation and noise.");
		} else if ("double".equals(r.windows()) || "triple".equals(r.windows())) {
			envelope += 5;
		}
		envelope = adjustForCondition(envelope, r.facadeCondition(), issues, "Facade");
		components.add(component("Insulation and facade", envelope, r.externalInsulation() == null && r.facadeCondition() == null, issues, ask));

		// Maintenance and common areas ---------------------------------------------------------
		issues = new ArrayList<>();
		ask = new ArrayList<>();
		int maintenance = 70;
		maintenance = adjustForCondition(maintenance, r.commonAreasCondition(), issues, "Common areas");
		if (r.plannedWorks() != null && !r.plannedWorks().isBlank()) {
			issues.add("Planned works: " + r.plannedWorks().trim());
			ask.add("Ask for the minutes of the general assembly that approved the works and each flat's share.");
			points.add("Planned building works" + (r.plannedLevy() != null ? String.format(Locale.UK, " (levy about €%,.0f for this flat)", r.plannedLevy()) : "")
					+ ": agree in the contract that the seller pays works voted before completion.");
		}
		if (Boolean.FALSE.equals(r.buildingPermit())) {
			maintenance -= 25;
			issues.add("No building permit recorded: check legality before going further.");
			points.add("Building permit missing: a legal blocker until the engineer's certificate confirms the building is legal or regularised.");
		}
		if (age > 40 && r.commonAreasCondition() == null) ask.add("What major works have been done in the last 10 years (plumbing risers, facade, roof)?");
		components.add(component("Maintenance and legality", maintenance, r.commonAreasCondition() == null && r.buildingPermit() == null, issues, ask));

		// Shared costs ---------------------------------------------------------------------------
		Double perSqm = r.monthlyCommonCharges() == null ? null : r.monthlyCommonCharges() / r.propertySizeSqm();
		boolean heating = Boolean.TRUE.equals(r.chargesIncludeHeating());
		double low = CHARGES_TYPICAL_LOW + (heating ? HEATING_ALLOWANCE : 0);
		double high = CHARGES_TYPICAL_HIGH + (heating ? HEATING_ALLOWANCE : 0);
		String assessment;
		if (perSqm == null) assessment = "Not provided: ask for the last 12 months of common-charge statements.";
		else if (perSqm > high) {
			assessment = String.format(Locale.UK, "High: €%.2f per m² a month (typical €%.2f-%.2f%s).", perSqm, low, high, heating ? " with heating" : "");
			points.add(String.format(Locale.UK, "Common charges of €%.0f a month are above the typical range.", r.monthlyCommonCharges()));
		} else if (perSqm < low * 0.6) {
			assessment = String.format(Locale.UK, "Unusually low: €%.2f per m² a month; check that maintenance is not being deferred.", perSqm);
		} else {
			assessment = String.format(Locale.UK, "Within the typical range: €%.2f per m² a month.", perSqm);
		}
		SharedCosts shared = new SharedCosts(r.monthlyCommonCharges(), perSqm == null ? null : Math.round(perSqm * 100) / 100.0,
				low, high, assessment, r.plannedLevy(), r.reserveFund());

		int score = (int) Math.round(components.stream().mapToInt(Component::score).average().orElse(0));
		long unknown = components.stream().filter(c -> c.rating().equals("unknown")).count();
		int completeness = (int) Math.round(100.0 * (components.size() - unknown) / components.size());
		List<String> notes = List.of(
				"Ratings are rule-based from the details given; they are not a structural survey.",
				"Typical common-charge range is a reference for Athens blocks of flats, not market data.");
		return new Result(score, completeness, List.copyOf(components), shared, List.copyOf(points), notes);
	}

	private static int adjustForCondition(int score, String condition, List<String> issues, String what) {
		if (condition == null) return score;
		return switch (condition) {
			case "poor" -> {
				issues.add(what + " reported in poor condition.");
				yield score - 30;
			}
			case "fair" -> score - 10;
			case "excellent" -> score + 10;
			default -> score;
		};
	}

	private static Component component(String name, int score, boolean unknown, List<String> issues, List<String> ask) {
		int s = Math.clamp(score, 5, 98);
		String rating = unknown && issues.isEmpty() ? "unknown" : s >= 80 ? "good" : s >= 65 ? "fair" : s >= 45 ? "attention" : "poor";
		return new Component(name, rating, s, List.copyOf(issues), List.copyOf(ask));
	}
}
