package com.propertyapp.renovation;

import static org.assertj.core.api.Assertions.assertThat;

import com.propertyapp.renovation.RenovationPlanner.Concern;
import com.propertyapp.renovation.RenovationPlanner.Quote;
import com.propertyapp.renovation.RenovationPlanner.Request;
import com.propertyapp.renovation.RenovationPlanner.Scenario;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenovationPlannerTest {

	@Test
	void threeScenariosWithUpliftFromTheValuationConditionLadder() {
		var r = RenovationPlanner.plan(new Request(70, 1, 1978, "fair", 200_000.0, null, null));
		assertThat(r.scenarios()).extracting(Scenario::tier).containsExactly("basic", "medium", "premium");
		Scenario basic = r.scenarios().get(0);
		Scenario medium = r.scenarios().get(1);
		Scenario premium = r.scenarios().get(2);
		assertThat(basic.conditionAfter()).isEqualTo("fair");
		assertThat(basic.valueUplift()).isZero();
		// fair (−6 %) to good (0 %): 200,000 / 0.94; to excellent (+7 %): × 1.07 / 0.94
		assertThat(medium.valueAfter()).isEqualTo(213_000);
		assertThat(premium.valueAfter()).isEqualTo(228_000);
		assertThat(basic.costHigh()).isLessThan(medium.costLow());
		assertThat(medium.costLow() + medium.costHigh()).isLessThan(premium.costLow() + premium.costHigh()); // ranges may overlap
		assertThat(premium.items()).hasSize(12);
	}

	@Test
	void verifiedQuoteReplacesTheBandAndConcernsAreMatched() {
		var r = RenovationPlanner.plan(new Request(70, 1, 1978, "fair", null,
				List.of(new Quote("kitchen", 8_200, "Contractor quote")),
				List.of(new Concern("kitchen", "Older kitchen"), new Concern("living-room", "Windows may need replacement"))));
		var medium = r.scenarios().get(1);
		assertThat(medium.items()).filteredOn(i -> i.category().equals("kitchen")).singleElement()
				.satisfies(i -> {
					assertThat(i.low()).isEqualTo(8_200);
					assertThat(i.basis()).startsWith("Verified quote");
				});
		assertThat(medium.valueAfter()).isNull();
		assertThat(medium.addresses()).containsExactly("Older kitchen");
		assertThat(r.scenarios().get(2).addresses()).containsExactly("Older kitchen", "Windows may need replacement");
		assertThat(r.notes()).anyMatch(n -> n.startsWith("No current value given"));
	}
}
