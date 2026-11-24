package com.propertyapp.investment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.List;
import org.junit.jupiter.api.Test;

class InvestmentCalculatorTest {

	static InvestmentCalculator.Request cash(double rent, double vacancyPct) {
		return new InvestmentCalculator.Request(200_000, 0, 0, rent, vacancyPct, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0,
				0.0, 0.0, 10, 0.0);
	}

	@Test
	void yieldsForASimpleCashPurchase() {
		var r = InvestmentCalculator.calculate(cash(1000, 0));
		assertThat(r.grossYieldPct()).isEqualTo(6.0);
		assertThat(r.netYieldPct()).isEqualTo(6.0);
		assertThat(r.capRatePct()).isEqualTo(6.0);
		assertThat(r.monthlyCashFlowAfterTax()).isEqualTo(1000);
		// No growth, no tax, sold at cost after 10 years: IRR equals the yield.
		assertThat(r.irrPct()).isCloseTo(6.0, offset(0.05));
		assertThat(r.paybackYear()).isNull();
		assertThat(r.equityMultiple()).isEqualTo(1.6);
	}

	@Test
	void vacancyLeverageAndGreekTaxBrackets() {
		var r = InvestmentCalculator.calculate(new InvestmentCalculator.Request(200_000, 16_000, 10_000, 1000, 10.0, 50, 300,
				150, 5.0, 0.0, null, 160_000, 4, 25, 2.0, 2.0, 10, 3.0));
		var y1 = r.years().getFirst();
		assertThat(y1.rentalIncome()).isEqualTo(10_800);                  // 12,000 less 10 % vacancy
		assertThat(y1.operatingCosts()).isEqualTo(600 + 300 + 150 + 600); // charges, tax, insurance, 5 % maintenance
		assertThat(y1.incomeTax()).isEqualTo(Math.round((10_800 - 1650) * 0.15));
		assertThat(y1.debtService()).isCloseTo(845 * 12, offset(15.0));
		assertThat(r.monthlyCashFlowAfterTax()).isNegative();
		assertThat(r.warnings()).anyMatch(w -> w.startsWith("Negative cash flow")).anyMatch(w -> w.startsWith("Debt service coverage"));
		assertThat(r.years()).hasSize(10);
		assertThat(r.years().getLast().loanBalance()).isLessThan(160_000);
	}

	@Test
	void helpers() {
		assertThat(InvestmentCalculator.irr(List.of(-100.0, 110.0))).isCloseTo(0.10, offset(1e-6));
		assertThat(InvestmentCalculator.irr(List.of(100.0, 110.0))).isNull();
		assertThat(InvestmentCalculator.greekRentalTax(20_000)).isEqualTo(12_000 * 0.15 + 8_000 * 0.35);
		assertThat(InvestmentCalculator.greekRentalTax(40_000)).isEqualTo(1800 + 23_000 * 0.35 + 5_000 * 0.45);
	}
}
