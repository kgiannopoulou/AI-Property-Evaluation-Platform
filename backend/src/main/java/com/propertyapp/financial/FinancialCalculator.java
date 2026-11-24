package com.propertyapp.financial;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Financial Calculator: the cash needed to buy and the true monthly cost of owning a home.
 *
 * <p>Greek-style purchase costs with published default rates (each one overridable), an annuity
 * mortgage with a +2 percentage-point stress test, and ownership costs. Figures the caller did not
 * supply and that cannot be derived honestly (e.g. ENFIA property tax, which depends on the zone
 * value of the street) are reported as "not provided" instead of being invented; the few that are
 * estimated (utilities, maintenance, insurance) are labelled as estimates.
 */
public final class FinancialCalculator {

	private FinancialCalculator() {}

	/** Monthly electricity, water and heating for an average household, EUR per m², by energy class. */
	static final Map<String, Double> UTILITIES_PER_SQM = Map.of(
			"A+", 0.55, "A", 0.65, "B+", 0.8, "B", 0.9, "C", 1.05, "D", 1.25, "E", 1.45, "F", 1.7, "G", 2.0);

	public record Request(
			@Positive double price,
			/** transfer-tax (resale) or vat (new build sold with VAT). */
			@NotNull @Pattern(regexp = "transfer-tax|vat") String taxBasis,
			/** Objective (tax) value; transfer tax is charged on the higher of price and objective value. */
			@Positive Double objectiveValue,
			/** First-home exemption from transfer tax, if the buyer qualifies (they must check eligibility). */
			boolean firstHomeExemption,
			@Valid Rates rates,
			@Valid Mortgage mortgage,
			@Valid Ownership ownership) {}

	/** Rates as fractions (0.0309 = 3.09 %). Defaults are typical Greek rates; confirm with the notary. */
	public record Rates(
			@DecimalMin("0") @DecimalMax("0.1") Double transferTax,
			@DecimalMin("0") @DecimalMax("0.3") Double vat,
			@DecimalMin("0") @DecimalMax("0.05") Double notary,
			@DecimalMin("0") @DecimalMax("0.05") Double lawyer,
			@DecimalMin("0") @DecimalMax("0.05") Double registry,
			@DecimalMin("0") @DecimalMax("0.05") Double agent,
			Boolean vatOnFees) {

		static final Rates DEFAULTS = new Rates(0.0309, 0.24, 0.008, 0.01, 0.00575, 0.02, true);

		Rates withDefaults() {
			return new Rates(or(transferTax, DEFAULTS.transferTax), or(vat, DEFAULTS.vat), or(notary, DEFAULTS.notary),
					or(lawyer, DEFAULTS.lawyer), or(registry, DEFAULTS.registry), or(agent, DEFAULTS.agent),
					vatOnFees == null ? DEFAULTS.vatOnFees : vatOnFees);
		}
	}

	/** {@code downPayment} or {@code loanToValue} (0-1); without either, a cash purchase. */
	public record Mortgage(
			@PositiveOrZero Double downPayment,
			@DecimalMin("0") @DecimalMax("1") Double loanToValue,
			@DecimalMin("0") @DecimalMax("20") double annualRatePct,
			@Min(1) @Max(40) int years) {}

	public record Ownership(
			@DecimalMin("15") @DecimalMax("2000") double sizeSqm,
			@Pattern(regexp = "A\\+|A|B\\+|B|C|D|E|F|G") String energyClass,
			@Min(1850) @Max(2100) Integer yearBuilt,
			@PositiveOrZero Double monthlyCommonCharges,
			@PositiveOrZero Double annualPropertyTax,
			@PositiveOrZero Double annualInsurance,
			@PositiveOrZero Double monthlyUtilities,
			@DecimalMin("0") @DecimalMax("0.05") Double maintenanceRate) {}

	/** @param estimate true when the engine estimated the amount instead of receiving it */
	public record Line(String label, double amount, String basis, boolean estimate) {}

	public record Loan(
			double amount, double downPayment, double monthlyPayment, double totalInterest, double stressMonthlyPayment,
			double annualRatePct, int years) {}

	public record Result(
			double price,
			List<Line> purchaseCosts,
			double totalPurchaseCosts,
			double purchaseCostsPct,
			Loan loan,
			double cashNeeded,
			List<Line> monthlyCosts,
			List<String> notProvided,
			double monthlyTotal,
			double annualTotal,
			double firstYearTotal,
			List<String> assumptions) {}

	public static Result calculate(Request r) {
		Rates rates = (r.rates() == null ? Rates.DEFAULTS : r.rates()).withDefaults();
		double price = r.price();
		List<Line> costs = new ArrayList<>();
		List<String> assumptions = new ArrayList<>();
		double feeVat = rates.vatOnFees() ? 1 + rates.vat() : 1;

		if (r.taxBasis().equals("vat")) {
			costs.add(new Line("VAT on a new build", round(price * rates.vat()), pct(rates.vat()) + " of the price", false));
			assumptions.add("VAT applies only to new buildings sold by the developer; check whether a suspension is in force.");
		} else if (r.firstHomeExemption()) {
			costs.add(new Line("Transfer tax", 0, "First-home exemption claimed", false));
			assumptions.add("The first-home exemption has limits on value, size and family status; the notary confirms eligibility.");
		} else {
			double base = Math.max(price, r.objectiveValue() == null ? 0 : r.objectiveValue());
			costs.add(new Line("Transfer tax", round(base * rates.transferTax()), pct(rates.transferTax()) + " of "
					+ (base > price ? "the objective value" : "the price"), false));
			if (r.objectiveValue() == null) assumptions.add("Objective value not given: transfer tax computed on the price.");
		}
		costs.add(new Line("Notary", round(price * rates.notary() * feeVat), pct(rates.notary()) + vatText(rates), false));
		costs.add(new Line("Lawyer", round(price * rates.lawyer() * feeVat), pct(rates.lawyer()) + vatText(rates), false));
		costs.add(new Line("Land registry / cadastre", round(price * rates.registry()), pct(rates.registry()) + " of the price", false));
		if (rates.agent() > 0) {
			costs.add(new Line("Agent commission", round(price * rates.agent() * feeVat), pct(rates.agent()) + vatText(rates), false));
		}
		double totalCosts = costs.stream().mapToDouble(Line::amount).sum();

		Loan loan = null;
		double down = price;
		if (r.mortgage() != null) {
			Mortgage m = r.mortgage();
			double amount = m.downPayment() != null ? Math.max(0, price - m.downPayment())
					: m.loanToValue() != null ? price * m.loanToValue() : 0;
			down = price - amount;
			if (amount > 0) {
				double monthly = annuity(amount, m.annualRatePct(), m.years());
				double stress = annuity(amount, m.annualRatePct() + 2, m.years());
				loan = new Loan(round(amount), round(down), round(monthly), round(monthly * m.years() * 12 - amount),
						round(stress), m.annualRatePct(), m.years());
				assumptions.add("Stress test: the same loan at " + (m.annualRatePct() + 2) + "% (+2 percentage points).");
			}
		}

		List<Line> monthly = new ArrayList<>();
		List<String> notProvided = new ArrayList<>();
		if (loan != null) monthly.add(new Line("Mortgage payment", loan.monthlyPayment(), loan.years() + " years at " + loan.annualRatePct() + "%", false));
		Ownership o = r.ownership();
		if (o != null) {
			if (o.monthlyCommonCharges() != null) monthly.add(new Line("Common charges", round(o.monthlyCommonCharges()), "Declared", false));
			else notProvided.add("Common charges: ask the building manager for the last 12 months of statements.");
			if (o.annualPropertyTax() != null) monthly.add(new Line("Property tax (ENFIA)", round(o.annualPropertyTax() / 12), "Declared annual amount / 12", false));
			else notProvided.add("Property tax (ENFIA): depends on the zone value of the street; ask the seller for the last ENFIA statement.");
			double insurance = o.annualInsurance() != null ? o.annualInsurance() : o.sizeSqm() * 1.5;
			monthly.add(new Line("Home insurance", round(insurance / 12), o.annualInsurance() != null ? "Declared" : "Estimate: €1.50 per m² a year", o.annualInsurance() == null));
			if (o.monthlyUtilities() != null) {
				monthly.add(new Line("Utilities", round(o.monthlyUtilities()), "Declared", false));
			} else if (o.energyClass() != null) {
				double perSqm = UTILITIES_PER_SQM.get(o.energyClass());
				monthly.add(new Line("Utilities", round(o.sizeSqm() * perSqm),
						String.format(java.util.Locale.UK, "Estimate: €%.2f per m² a month for class %s", perSqm, o.energyClass()), true));
			} else {
				notProvided.add("Utilities: give the energy class or last bills for an estimate.");
			}
			double rate = o.maintenanceRate() != null ? o.maintenanceRate()
					: o.yearBuilt() != null && java.time.Year.now().getValue() - o.yearBuilt() > 30 ? 0.01 : 0.005;
			monthly.add(new Line("Maintenance reserve", round(price * rate / 12), pct(rate) + " of the price a year"
					+ (o.maintenanceRate() == null ? " (1% for buildings over 30 years old)" : ""), o.maintenanceRate() == null));
		}
		double monthlyTotal = round(monthly.stream().mapToDouble(Line::amount).sum());
		return new Result(price, List.copyOf(costs), round(totalCosts), round(totalCosts / price * 1000) / 10.0, loan,
				round(down + totalCosts), List.copyOf(monthly), List.copyOf(notProvided), monthlyTotal,
				round(monthlyTotal * 12), round(down + totalCosts + monthlyTotal * 12), List.copyOf(assumptions));
	}

	/** Standard annuity payment for a monthly-amortising loan. */
	public static double annuity(double principal, double annualRatePct, int years) {
		double r = annualRatePct / 100 / 12;
		int n = years * 12;
		if (r == 0) return principal / n;
		return principal * r / (1 - Math.pow(1 + r, -n));
	}

	private static String vatText(Rates rates) {
		return " of the price" + (rates.vatOnFees() ? " + " + pct(rates.vat()) + " VAT" : "");
	}

	private static String pct(double fraction) {
		return new java.math.BigDecimal(fraction * 100).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
	}

	static double round(double v) {
		return Math.round(v);
	}

	private static double or(Double v, double fallback) {
		return v == null ? fallback : v;
	}
}
