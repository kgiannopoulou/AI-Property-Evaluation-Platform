package com.propertyapp.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.propertyapp.subscriptions.PlanCatalog.Feature;
import com.propertyapp.subscriptions.PlanCatalog.Plan;
import org.junit.jupiter.api.Test;

class PlanCatalogTest {

	@Test
	void pricesAndLimitsAreThePhase9Hypotheses() {
		assertThat(PlanCatalog.PLANS).extracting(Plan::id, Plan::monthlyPriceCents, Plan::analysesPerMonth).containsExactly(
				org.assertj.core.groups.Tuple.tuple("free", 0, 2),
				org.assertj.core.groups.Tuple.tuple("premium", 999, 20),
				org.assertj.core.groups.Tuple.tuple("professional", 2999, 100),
				org.assertj.core.groups.Tuple.tuple("business", null, null));
		assertThat(PlanCatalog.get("business").selfServe()).isFalse();
	}

	@Test
	void eachPlanIncludesEverythingBelowIt() {
		for (int i = 1; i < PlanCatalog.PLANS.size(); i++) {
			assertThat(PlanCatalog.PLANS.get(i).features()).containsAll(PlanCatalog.PLANS.get(i - 1).features());
		}
	}

	@Test
	void featuresPerPlanMatchTheBrief() {
		Plan free = PlanCatalog.get("free");
		assertThat(free.has(Feature.BASIC_INSPECTION)).isTrue();
		assertThat(free.has(Feature.FULL_INSPECTION)).isFalse();
		assertThat(free.has(Feature.REPORTS)).isFalse();
		assertThat(PlanCatalog.get("premium").has(Feature.ADVANCED_VALUATION)).isTrue();
		assertThat(PlanCatalog.get("premium").has(Feature.REPORTS)).isTrue();
		assertThat(PlanCatalog.get("premium").has(Feature.INVESTMENT)).isFalse();
		assertThat(PlanCatalog.get("professional").has(Feature.CLIENT_REPORTS)).isTrue();
		assertThat(PlanCatalog.get("professional").has(Feature.API_ACCESS)).isFalse();
		assertThat(PlanCatalog.get("business").features()).contains(Feature.TEAMS, Feature.BULK_ANALYSIS, Feature.API_ACCESS);
		assertThat(PlanCatalog.cheapestWith(Feature.INVESTMENT).id()).isEqualTo("professional");
		assertThat(Feature.FULL_INSPECTION.id()).isEqualTo("full-inspection");
	}
}
