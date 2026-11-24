package com.propertyapp.comparison;

import static org.assertj.core.api.Assertions.assertThat;

import com.propertyapp.comparison.PropertyComparison.Candidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PropertyComparisonTest {

	static final Candidate A = new Candidate("a", "Pangrati 2-bed", 220_000, 70, 8.0, 72.0, 85.0, 60.0, "D", 1_100.0, 4.1, 25_000.0, "issues");
	static final Candidate B = new Candidate("b", "Koukaki 2-bed", 240_000, 80, -2.0, 80.0, 88.0, 75.0, "C", 1_200.0, 4.4, 10_000.0, "ready");
	static final Candidate C = new Candidate("c", "Kallithea 2-bed", 180_000, 75, null, 60.0, null, null, "E", 950.0, null, null, null);

	@Test
	void bestPerMeasureAndRanking() {
		var r = PropertyComparison.compare(new PropertyComparison.Request(List.of(A, B, C), null));
		var price = r.rows().getFirst();
		assertThat(price.values()).containsExactly(3142.86, 3000.0, 2400.0);
		assertThat(price.best()).containsExactly(2); // lowest €/m² wins
		var delta = r.rows().get(1);
		assertThat(delta.values()).containsExactly(8.0, -2.0, null);
		assertThat(delta.best()).containsExactly(1);
		assertThat(r.ranking().getFirst().id()).isEqualTo("b");
		assertThat(r.ranking()).extracting(PropertyComparison.Ranking::rank).containsExactly(1, 2, 3);
	}

	@Test
	void missingMeasuresAreListedNotGuessed() {
		var r = PropertyComparison.compare(new PropertyComparison.Request(List.of(A, B, C), null));
		var c = r.ranking().stream().filter(x -> x.id().equals("c")).findFirst().orElseThrow();
		assertThat(c.missing()).contains("Asking vs fair value", "Location score", "Building score", "Net rental yield");
		assertThat(r.notes()).anyMatch(n -> n.startsWith("Measures a property is missing"));
	}

	@Test
	void weightsChangeTheWinner() {
		var priceOnly = PropertyComparison.compare(new PropertyComparison.Request(List.of(A, B, C),
				Map.of("pricePerSqm", 100.0, "valueDeltaPct", 0.0, "conditionScore", 0.0, "locationScore", 0.0, "buildingScore", 0.0,
						"energy", 0.0, "monthlyOwnershipCost", 0.0, "netYieldPct", 0.0, "renovationCostMid", 0.0)));
		assertThat(priceOnly.ranking().getFirst().id()).isEqualTo("c");
		assertThat(priceOnly.ranking().getFirst().score()).isEqualTo(100.0);
	}
}
