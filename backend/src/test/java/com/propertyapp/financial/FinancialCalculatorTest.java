package com.propertyapp.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.propertyapp.financial.FinancialCalculator.Line;
import com.propertyapp.financial.FinancialCalculator.Mortgage;
import com.propertyapp.financial.FinancialCalculator.Ownership;
import com.propertyapp.financial.FinancialCalculator.Request;
import org.junit.jupiter.api.Test;

class FinancialCalculatorTest {

	static Request resale(Mortgage m, Ownership o) {
		return new Request(200_000, "transfer-tax", null, false, null, m, o);
	}

	@Test
	void greekPurchaseCostsWithDefaultRates() {
		var r = FinancialCalculator.calculate(resale(null, null));
		assertThat(r.purchaseCosts()).extracting(Line::label, Line::amount).containsExactly(
				org.assertj.core.groups.Tuple.tuple("Transfer tax", 6180.0),       // 3.09 %
				org.assertj.core.groups.Tuple.tuple("Notary", 1984.0),             // 0.8 % + 24 % VAT
				org.assertj.core.groups.Tuple.tuple("Lawyer", 2480.0),             // 1 % + VAT
				org.assertj.core.groups.Tuple.tuple("Land registry / cadastre", 1150.0),
				org.assertj.core.groups.Tuple.tuple("Agent commission", 4960.0));  // 2 % + VAT
		assertThat(r.totalPurchaseCosts()).isEqualTo(16_754);
		assertThat(r.purchaseCostsPct()).isEqualTo(8.4);
		assertThat(r.cashNeeded()).isEqualTo(216_754); // cash purchase
		assertThat(r.purchaseCosts().getFirst().basis()).isEqualTo("3.09% of the price");
	}

	@Test
	void transferTaxUsesTheHigherObjectiveValueAndRespectsExemptionAndVat() {
		var objective = FinancialCalculator.calculate(new Request(200_000, "transfer-tax", 250_000.0, false, null, null, null));
		assertThat(objective.purchaseCosts().getFirst().amount()).isEqualTo(7725);
		var exempt = FinancialCalculator.calculate(new Request(200_000, "transfer-tax", null, true, null, null, null));
		assertThat(exempt.purchaseCosts().getFirst().amount()).isZero();
		var vat = FinancialCalculator.calculate(new Request(200_000, "vat", null, false, null, null, null));
		assertThat(vat.purchaseCosts().getFirst().amount()).isEqualTo(48_000);
	}

	@Test
	void mortgageWithStressTest() {
		var r = FinancialCalculator.calculate(resale(new Mortgage(null, 0.8, 4, 25), null));
		assertThat(r.loan().amount()).isEqualTo(160_000);
		assertThat(r.loan().monthlyPayment()).isCloseTo(845, offset(1.0));
		assertThat(r.loan().stressMonthlyPayment()).isGreaterThan(r.loan().monthlyPayment() + 150);
		assertThat(r.cashNeeded()).isEqualTo(40_000 + 16_754);
	}

	@Test
	void ownershipCostsNeverInventTheUnknowable() {
		var r = FinancialCalculator.calculate(resale(new Mortgage(40_000.0, null, 4, 25),
				new Ownership(70, "D", 1978, 60.0, null, null, null, null)));
		assertThat(r.notProvided()).singleElement().asString().startsWith("Property tax (ENFIA)");
		assertThat(r.monthlyCosts()).extracting(Line::label)
				.containsExactly("Mortgage payment", "Common charges", "Home insurance", "Utilities", "Maintenance reserve");
		assertThat(r.monthlyCosts()).filteredOn(Line::estimate).extracting(Line::label)
				.containsExactly("Home insurance", "Utilities", "Maintenance reserve");
		// 70 m² × €1.25 (class D) = €87.50; maintenance 1 % of €200,000 a year for a 48-year-old building.
		assertThat(r.monthlyCosts().get(3).amount()).isEqualTo(88);
		assertThat(r.monthlyCosts().get(4).amount()).isEqualTo(167);
	}
}
