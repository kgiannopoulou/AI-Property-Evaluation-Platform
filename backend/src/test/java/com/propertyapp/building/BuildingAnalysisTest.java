package com.propertyapp.building;

import static org.assertj.core.api.Assertions.assertThat;

import com.propertyapp.building.BuildingAnalysis.Component;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BuildingAnalysisTest {

	static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

	@Test
	void olderBlockWithoutElevatorAndHighCharges() {
		var r = BuildingAnalysis.analyse(new BuildingAnalysis.Request(1975, 5, 10, 4, 70, "reinforced-concrete", null, null,
				0, null, null, null, 2005, "fair", false, "single", null, 150.0, false, null,
				"Facade repair voted in October", 2_500.0, true, true), TODAY);

		assertThat(r.components()).extracting(Component::component)
				.containsExactly("Structure", "Elevator", "Roof", "Insulation and facade", "Maintenance and legality");
		assertThat(r.components().getFirst().issues()).anyMatch(i -> i.contains("1959 seismic code"))
				.anyMatch(i -> i.contains("pilotis"));
		assertThat(r.components().get(1).rating()).isEqualTo("attention");
		assertThat(r.components().get(2).issues()).anyMatch(i -> i.contains("21 years old"));
		// €150 / 70 m² = €2.14 per m², above the typical €0.60-1.40 without heating.
		assertThat(r.sharedCosts().perSqm()).isEqualTo(2.14);
		assertThat(r.sharedCosts().assessment()).startsWith("High");
		assertThat(r.negotiationPoints())
				.anyMatch(p -> p.startsWith("No elevator to floor 4"))
				.anyMatch(p -> p.startsWith("Planned building works (levy about €2,500"))
				.anyMatch(p -> p.startsWith("Common charges of €150"));
	}

	@Test
	void unknownItemsAreUnknownNotGood() {
		var r = BuildingAnalysis.analyse(new BuildingAnalysis.Request(2005, null, null, null, 80, null, null, null,
				1, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null), TODAY);
		assertThat(r.components()).filteredOn(c -> c.rating().equals("unknown")).isNotEmpty();
		assertThat(r.completenessPct()).isLessThan(100);
		assertThat(r.sharedCosts().assessment()).startsWith("Not provided");
		assertThat(r.components().get(1).ask()).anyMatch(a -> a.contains("periodic inspection"));
	}
}
