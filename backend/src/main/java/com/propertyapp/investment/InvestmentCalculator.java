package com.propertyapp.investment;

import com.propertyapp.financial.FinancialCalculator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.ArrayList;
import java.util.List;

/**
 * Investment Calculator for a buy-to-let: rental yield, ROI, vacancy and cash flow.
 *
 * <p>Year-by-year projection with rent growth, vacancy, operating costs, rental income tax,
 * mortgage amortisation and a sale at the end of the holding period; returns gross and net
 * yield, cap rate, cash-on-cash, break-even occupancy, DSCR, IRR and the equity multiple.
 * Rental income tax defaults to the Greek brackets on net rental income (15 % to €12,000,
 * 35 % to €35,000, 45 % above) unless the caller gives a flat rate.
 */
public final class InvestmentCalculator {

	private InvestmentCalculator() {}

	public record Request(
			@Positive double purchasePrice,
			@PositiveOrZero double purchaseCosts,
			@PositiveOrZero double renovationCosts,
			@Positive double monthlyRent,
			@DecimalMin("0") @DecimalMax("60") Double vacancyPct,
			@PositiveOrZero double monthlyCommonCharges,
			@PositiveOrZero double annualPropertyTax,
			@PositiveOrZero double annualInsurance,
			@DecimalMin("0") @DecimalMax("30") Double maintenancePctOfRent,
			@DecimalMin("0") @DecimalMax("30") Double managementPctOfRent,
			@DecimalMin("0") @DecimalMax("60") Double flatIncomeTaxPct,
			@PositiveOrZero double loanAmount,
			@DecimalMin("0") @DecimalMax("20") double loanRatePct,
			@Min(0) @Max(40) int loanYears,
			@DecimalMin("-10") @DecimalMax("15") Double appreciationPct,
			@DecimalMin("-10") @DecimalMax("15") Double rentGrowthPct,
			@Min(1) @Max(40) Integer holdingYears,
			@DecimalMin("0") @DecimalMax("15") Double sellingCostsPct) {}

	public record Year(
			int year, double rentalIncome, double operatingCosts, double netOperatingIncome, double debtService,
			double interest, double incomeTax, double cashFlowAfterTax, double loanBalance, double propertyValue) {}

	public record Result(
			double totalInvestment,
			double equity,
			double grossYieldPct,
			double netYieldPct,
			double capRatePct,
			double cashOnCashPct,
			double monthlyCashFlowBeforeTax,
			double monthlyCashFlowAfterTax,
			double breakEvenOccupancyPct,
			Double debtServiceCoverage,
			Double irrPct,
			double totalProfit,
			double totalRoiPct,
			double equityMultiple,
			Integer paybackYear,
			List<Year> years,
			List<String> assumptions,
			List<String> warnings) {}

	public static Result calculate(Request r) {
		double vacancy = or(r.vacancyPct(), 8) / 100;
		double maintenance = or(r.maintenancePctOfRent(), 5) / 100;
		double management = or(r.managementPctOfRent(), 0) / 100;
		double appreciation = or(r.appreciationPct(), 2) / 100;
		double rentGrowth = or(r.rentGrowthPct(), 2) / 100;
		int hold = r.holdingYears() == null ? 10 : r.holdingYears();
		double sellingCosts = or(r.sellingCostsPct(), 3) / 100;

		double total = r.purchasePrice() + r.purchaseCosts() + r.renovationCosts();
		double loan = Math.min(r.loanAmount(), total);
		double equity = total - loan;
		boolean hasLoan = loan > 0 && r.loanYears() > 0;
		double monthlyPayment = hasLoan ? FinancialCalculator.annuity(loan, r.loanRatePct(), r.loanYears()) : 0;

		List<Year> years = new ArrayList<>();
		List<Double> flows = new ArrayList<>();
		flows.add(-equity);
		double balance = loan;
		double cumulative = -equity;
		Integer payback = null;
		double value = r.purchasePrice();
		for (int y = 1; y <= hold; y++) {
			double grossRent = r.monthlyRent() * 12 * Math.pow(1 + rentGrowth, y - 1);
			double income = grossRent * (1 - vacancy);
			double opex = r.monthlyCommonCharges() * 12 + r.annualPropertyTax() + r.annualInsurance()
					+ grossRent * (maintenance + management);
			double noi = income - opex;
			double interest = 0;
			double debt = 0;
			for (int m = 0; m < 12 && hasLoan && balance > 0.005 && (y - 1) * 12 + m < r.loanYears() * 12; m++) {
				double i = balance * r.loanRatePct() / 100 / 12;
				interest += i;
				double payment = Math.min(monthlyPayment, balance + i);
				balance -= payment - i;
				debt += payment;
			}
			// Mortgage interest is not deductible from Greek rental income; tax is on income less expenses.
			double taxable = Math.max(0, income - opex);
			double tax = r.flatIncomeTaxPct() != null ? taxable * r.flatIncomeTaxPct() / 100 : greekRentalTax(taxable);
			double cash = noi - debt - tax;
			value = r.purchasePrice() * Math.pow(1 + appreciation, y);
			years.add(new Year(y, round(income), round(opex), round(noi), round(debt), round(interest), round(tax),
					round(cash), round(Math.max(balance, 0)), round(value)));
			double flow = cash;
			if (y == hold) flow += value * (1 - sellingCosts) - Math.max(balance, 0);
			flows.add(flow);
			cumulative += cash;
			if (payback == null && cumulative >= 0) payback = y;
		}

		Year first = years.getFirst();
		double grossRentYear1 = r.monthlyRent() * 12;
		double saleNet = value * (1 - sellingCosts) - Math.max(balance, 0);
		double totalCash = years.stream().mapToDouble(Year::cashFlowAfterTax).sum();
		double profit = totalCash + saleNet - equity;

		List<String> assumptions = List.of(
				String.format(java.util.Locale.UK, "Vacancy %.0f%%, maintenance %.0f%% and management %.0f%% of rent.", vacancy * 100, maintenance * 100, management * 100),
				String.format(java.util.Locale.UK, "Rent grows %.1f%% and the property %.1f%% a year; sold after %d years with %.0f%% selling costs.", rentGrowth * 100, appreciation * 100, hold, sellingCosts * 100),
				r.flatIncomeTaxPct() != null ? "Rental income tax at a flat " + r.flatIncomeTaxPct() + "%."
						: "Rental income tax at the Greek brackets (15% / 35% / 45%) on income less operating costs; the owner's other income and any reductions are not considered.",
				"Capital gains tax on the sale is not included.");
		List<String> warnings = new ArrayList<>();
		if (first.cashFlowAfterTax() < 0) warnings.add("Negative cash flow in year 1: the investment needs a monthly top-up.");
		Double dscr = first.debtService() > 0 ? round2(first.netOperatingIncome() / first.debtService()) : null;
		if (dscr != null && dscr < 1.2) warnings.add("Debt service coverage below 1.2: lenders may consider the loan too large for the rent.");

		Double irr = irr(flows);
		return new Result(round(total), round(equity), pct(grossRentYear1 / r.purchasePrice()),
				pct(first.netOperatingIncome() / total), pct(first.netOperatingIncome() / r.purchasePrice()),
				equity > 0 ? pct(first.cashFlowAfterTax() / equity) : 0,
				round((first.netOperatingIncome() - first.debtService()) / 12), round(first.cashFlowAfterTax() / 12),
				pct((first.operatingCosts() + first.debtService()) / grossRentYear1), dscr,
				irr == null ? null : pct(irr), round(profit), equity > 0 ? pct(profit / equity) : 0,
				equity > 0 ? round2((totalCash + saleNet) / equity) : 0, payback, List.copyOf(years), assumptions,
				List.copyOf(warnings));
	}

	/** Greek rental income tax brackets (individuals). */
	static double greekRentalTax(double taxable) {
		double tax = Math.min(taxable, 12_000) * 0.15;
		if (taxable > 12_000) tax += (Math.min(taxable, 35_000) - 12_000) * 0.35;
		if (taxable > 35_000) tax += (taxable - 35_000) * 0.45;
		return tax;
	}

	/** Internal rate of return by bisection; null when the flows do not change sign. */
	static Double irr(List<Double> flows) {
		double lo = -0.99;
		double hi = 1.0;
		double fLo = npv(flows, lo);
		double fHi = npv(flows, hi);
		if (Math.signum(fLo) == Math.signum(fHi)) return null;
		for (int i = 0; i < 200; i++) {
			double mid = (lo + hi) / 2;
			double f = npv(flows, mid);
			if (Math.abs(f) < 1e-6) return mid;
			if (Math.signum(f) == Math.signum(fLo)) {
				lo = mid;
				fLo = f;
			} else {
				hi = mid;
			}
		}
		return (lo + hi) / 2;
	}

	static double npv(List<Double> flows, double rate) {
		double v = 0;
		for (int t = 0; t < flows.size(); t++) v += flows.get(t) / Math.pow(1 + rate, t);
		return v;
	}

	private static double pct(double fraction) {
		return Math.round(fraction * 1000) / 10.0;
	}

	private static double round(double v) {
		return Math.round(v);
	}

	private static double round2(double v) {
		return Math.round(v * 100) / 100.0;
	}

	private static double or(Double v, double fallback) {
		return v == null ? fallback : v;
	}
}
