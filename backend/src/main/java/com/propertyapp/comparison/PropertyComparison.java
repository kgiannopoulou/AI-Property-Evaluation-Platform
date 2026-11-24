package com.propertyapp.comparison;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Property Comparison: several properties side by side, the best one on each measure and a
 * weighted ranking. A property missing a measure is not given a made-up value; the measure is
 * left out of its total and the gap is listed.
 */
public final class PropertyComparison {

	private PropertyComparison() {}

	public record Request(
			@NotNull @Size(min = 2, max = 6) List<@Valid Candidate> properties,
			/** Measure → weight (any positive numbers; normalised). Default weights when absent. */
			Map<String, @DecimalMin("0") @DecimalMax("100") Double> weights) {}

	/** Any measure may be null when it has not been computed for that property. */
	public record Candidate(
			@NotBlank @Size(max = 64) String id,
			@NotBlank @Size(max = 120) String title,
			@Positive double askingPrice,
			@Positive double sizeSqm,
			Double valueDeltaPct,
			@DecimalMin("0") @DecimalMax("100") Double conditionScore,
			@DecimalMin("0") @DecimalMax("100") Double locationScore,
			@DecimalMin("0") @DecimalMax("100") Double buildingScore,
			String energyClass,
			Double monthlyOwnershipCost,
			Double netYieldPct,
			Double renovationCostMid,
			String documentReadiness) {}

	record Measure(String id, String label, boolean higherIsBetter, double defaultWeight, Function<Candidate, Double> value) {}

	static final Map<String, Double> ENERGY_POINTS = Map.of(
			"A+", 100.0, "A", 92.0, "B+", 84.0, "B", 76.0, "C", 64.0, "D", 52.0, "E", 40.0, "F", 28.0, "G", 16.0);

	static final List<Measure> MEASURES = List.of(
			new Measure("pricePerSqm", "Price per m²", false, 15, c -> c.askingPrice() / c.sizeSqm()),
			new Measure("valueDeltaPct", "Asking vs fair value", false, 20, Candidate::valueDeltaPct),
			new Measure("conditionScore", "Condition score", true, 15, Candidate::conditionScore),
			new Measure("locationScore", "Location score", true, 15, Candidate::locationScore),
			new Measure("buildingScore", "Building score", true, 10, Candidate::buildingScore),
			new Measure("energy", "Energy class", true, 5, c -> c.energyClass() == null ? null : ENERGY_POINTS.get(c.energyClass())),
			new Measure("monthlyOwnershipCost", "Monthly ownership cost", false, 10, Candidate::monthlyOwnershipCost),
			new Measure("netYieldPct", "Net rental yield", true, 5, Candidate::netYieldPct),
			new Measure("renovationCostMid", "Renovation needed", false, 5, Candidate::renovationCostMid),
			new Measure("documents", "Documents", true, 0, c -> c.documentReadiness() == null ? null
					: switch (c.documentReadiness()) {
						case "ready" -> 100.0;
						case "issues" -> 50.0;
						default -> 0.0;
					}));

	public record Row(String measure, String label, boolean higherIsBetter, List<Double> values, List<Integer> best, double weight) {}

	public record Ranking(String id, String title, int rank, double score, List<String> missing, List<String> strengths, List<String> weaknesses) {}

	public record Result(List<String> ids, List<Row> rows, List<Ranking> ranking, List<String> notes) {}

	public static Result compare(Request r) {
		List<Candidate> ps = r.properties();
		Map<String, Double> weights = new LinkedHashMap<>();
		for (Measure m : MEASURES) {
			double w = r.weights() != null && r.weights().containsKey(m.id()) ? r.weights().get(m.id()) : m.defaultWeight();
			weights.put(m.id(), w);
		}

		List<Row> rows = new ArrayList<>();
		double[][] normalised = new double[ps.size()][MEASURES.size()];
		boolean[][] known = new boolean[ps.size()][MEASURES.size()];
		for (int j = 0; j < MEASURES.size(); j++) {
			Measure m = MEASURES.get(j);
			List<Double> values = ps.stream().map(m.value()).map(v -> v == null ? null : Math.round(v * 100) / 100.0).toList();
			List<Double> present = values.stream().filter(v -> v != null).toList();
			List<Integer> best = new ArrayList<>();
			if (present.size() >= 2) {
				double min = present.stream().min(Double::compare).orElseThrow();
				double max = present.stream().max(Double::compare).orElseThrow();
				double target = m.higherIsBetter() ? max : min;
				for (int i = 0; i < ps.size(); i++) {
					Double v = values.get(i);
					if (v == null) continue;
					known[i][j] = true;
					double n = max == min ? 1 : (v - min) / (max - min);
					normalised[i][j] = m.higherIsBetter() ? n : 1 - n;
					if (v == target && max != min) best.add(i);
				}
			}
			rows.add(new Row(m.id(), m.label(), m.higherIsBetter(), values, List.copyOf(best), weights.get(m.id())));
		}

		List<Ranking> ranking = new ArrayList<>();
		for (int i = 0; i < ps.size(); i++) {
			double sum = 0;
			double weightSum = 0;
			List<String> missing = new ArrayList<>();
			List<String> strengths = new ArrayList<>();
			List<String> weaknesses = new ArrayList<>();
			for (int j = 0; j < MEASURES.size(); j++) {
				Measure m = MEASURES.get(j);
				double w = weights.get(m.id());
				if (w == 0) continue;
				if (!known[i][j]) {
					if (m.value().apply(ps.get(i)) == null) missing.add(m.label());
					continue;
				}
				sum += normalised[i][j] * w;
				weightSum += w;
				if (rows.get(j).best().contains(i)) strengths.add(m.label());
				else if (normalised[i][j] == 0) weaknesses.add(m.label());
			}
			double score = weightSum == 0 ? 0 : Math.round(sum / weightSum * 1000) / 10.0;
			ranking.add(new Ranking(ps.get(i).id(), ps.get(i).title(), 0, score, missing, strengths, weaknesses));
		}
		ranking.sort(Comparator.comparingDouble(Ranking::score).reversed());
		List<Ranking> ranked = new ArrayList<>();
		for (int k = 0; k < ranking.size(); k++) {
			Ranking x = ranking.get(k);
			ranked.add(new Ranking(x.id(), x.title(), k + 1, x.score(), List.copyOf(x.missing()), List.copyOf(x.strengths()), List.copyOf(x.weaknesses())));
		}

		List<String> notes = new ArrayList<>();
		notes.add("Scores are relative to the properties compared (0 = worst of the group, 100 = best), weighted by the measures' weights.");
		if (ranked.stream().anyMatch(x -> !x.missing().isEmpty())) {
			notes.add("Measures a property is missing are left out of its score rather than guessed; run the missing analyses for a fair comparison.");
		}
		return new Result(ps.stream().map(Candidate::id).toList(), List.copyOf(rows), List.copyOf(ranked), List.copyOf(notes));
	}
}
