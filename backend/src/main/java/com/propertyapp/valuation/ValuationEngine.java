package com.propertyapp.valuation;

import com.propertyapp.valuation.MarketRecord.Kind;
import com.propertyapp.valuation.ValuationReport.Adjustment;
import com.propertyapp.valuation.ValuationReport.ComparableResult;
import com.propertyapp.valuation.ValuationReport.Concern;
import com.propertyapp.valuation.ValuationReport.DataQuality;
import com.propertyapp.valuation.ValuationReport.Estimate;
import com.propertyapp.valuation.ValuationReport.Metrics;
import com.propertyapp.valuation.ValuationReport.Negotiation;
import com.propertyapp.valuation.ValuationReport.RepairLine;
import com.propertyapp.valuation.ValuationReport.Repairs;
import com.propertyapp.valuation.ValuationReport.Score;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Comparable-sales valuation engine.
 *
 * <ol>
 *   <li>Collect candidate records: same property type, same market (sales or rentals), size within
 *       ±40 %, observed in the last 36 months, within 3 km.
 *   <li>Use the smallest search radius (1, 2 or 3 km) that yields enough evidence.
 *   <li>Adjust each record's price per m² to the subject: asking-price margin, size, effective age,
 *       condition, floor and elevator, parking, energy class, bathrooms and, when enough sales exist
 *       to measure it, the local price trend. Records needing more than 35 % gross adjustment are
 *       dropped, then statistical outliers.
 *   <li>Weight what remains by evidence type, distance, recency, size similarity and how much it had
 *       to be adjusted; the estimate is the weighted median price per m² times the subject's size.
 *   <li>Grade the data. With too little or too inconsistent evidence the engine REFUSES to produce a
 *       figure and says what is missing.
 * </ol>
 *
 * <p>No AI model is involved in the price: every number can be traced to a record and a rule.
 */
public class ValuationEngine {

	public static final String ENGINE_ID = "comparables-v1";
	static final String ENGINE_SOURCE = "valuation-engine";

	static final double[] RADII_M = {1000, 2000, 3000};
	static final int MIN_COMPARABLES = 3;
	static final int TARGET_COMPARABLES = 6;
	static final int MIN_ASKING_ONLY = 5;
	static final double SIZE_TOLERANCE = 0.40;
	static final int MAX_AGE_MONTHS = 36;
	static final double MAX_GROSS_ADJUSTMENT = 0.35;
	static final double MAX_DISPERSION = 0.30;
	static final double MIN_EFFECTIVE_SAMPLE = 2.0;
	static final double OUTLIER_Z = 3.5;
	static final double OUTLIER_MIN_DEVIATION = 0.15;
	static final double ASKING_DISCOUNT_SALE = 0.05;
	static final double ASKING_DISCOUNT_RENT = 0.03;
	static final double MAX_MONTHLY_TREND = 0.01;

	private static final Map<String, Integer> SEVERITY_RANK = Map.of(
			"critical", 0, "high", 1, "medium", 2, "low", 3, "info", 4);

	private final MarketDataSource market;
	private final Clock clock;

	public ValuationEngine(MarketDataSource market, Clock clock) {
		this.market = market;
		this.clock = clock;
	}

	/** A candidate on its way through the pipeline. */
	private static final class Candidate {
		final MarketRecord record;
		final boolean userSupplied;
		String excludedReason;
		List<Adjustment> adjustments = List.of();
		double adjustedPerSqm;
		double gross;
		int attributesKnown;
		double monthsAgo;
		double weight;

		Candidate(MarketRecord record, boolean userSupplied) {
			this.record = record;
			this.userSupplied = userSupplied;
		}
	}

	public ValuationReport value(ValuationRequest request) {
		ValuationRequest.Subject s = request.subject();
		boolean rent = s.listingType().equals("rent");
		LocalDate today = LocalDate.now(clock);
		double step = rent ? 10 : 1000;
		double askingDiscount = rent ? ASKING_DISCOUNT_RENT : ASKING_DISCOUNT_SALE;
		Money money = new Money(rent);

		// 1. Candidates -------------------------------------------------------------------
		List<Candidate> all = new ArrayList<>();
		if (market.connected()) {
			List<String> listingTypes = rent ? List.of("rented", "rent-listing") : List.of("sold", "sale-listing");
			var search = new MarketDataSource.Search(s.location(), s.district(), s.propertyType(), listingTypes,
					s.sizeSqm(), SIZE_TOLERANCE, RADII_M[RADII_M.length - 1], today.minusMonths(MAX_AGE_MONTHS), 80);
			market.comparables(search).forEach(r -> all.add(new Candidate(r, false)));
		}
		int i = 0;
		for (ValuationRequest.ComparableInput c : request.additionalComparables()) {
			all.add(new Candidate(fromInput(c, ++i), true));
		}

		// 2. Screening --------------------------------------------------------------------
		for (Candidate c : all) {
			c.monthsAgo = ChronoUnit.DAYS.between(c.record.observedOn(), today) / 30.44;
			c.excludedReason = screen(s, c, rent, today);
		}
		double radius = chooseRadius(all);
		for (Candidate c : active(all)) {
			Double d = c.record.distanceM();
			if (d != null && d > radius) {
				c.excludedReason = String.format(Locale.UK, "%.0f m away: enough evidence within %.0f m", d, radius);
			}
		}

		// 3. Adjustments ------------------------------------------------------------------
		for (Candidate c : active(all)) {
			Adjustments.Result r = Adjustments.adjust(s, c.record, askingDiscount);
			c.adjustments = r.adjustments();
			c.adjustedPerSqm = c.record.pricePerSqm() * r.factor();
			c.gross = r.gross();
			c.attributesKnown = r.attributesKnown();
			if (c.gross > MAX_GROSS_ADJUSTMENT) {
				c.excludedReason = String.format(Locale.UK,
						"Needs %.0f%% of adjustments to match this property (limit %.0f%%)", c.gross * 100, MAX_GROSS_ADJUSTMENT * 100);
			}
		}
		removeOutliers(active(all));
		Double monthlyTrend = applyTrend(active(all));

		// 4. Weights and estimate ---------------------------------------------------------
		List<Candidate> used = active(all);
		for (Candidate c : used) c.weight = weight(c, s);
		double totalWeight = used.stream().mapToDouble(c -> c.weight).sum();
		for (Candidate c : used) c.weight = totalWeight > 0 ? c.weight / totalWeight : 0;

		double medianPerSqm = weightedMedian(used);
		double mean = used.stream().mapToDouble(c -> c.weight * c.adjustedPerSqm).sum();
		double variance = used.stream().mapToDouble(c -> c.weight * Math.pow(c.adjustedPerSqm - mean, 2)).sum();
		double cv = used.isEmpty() || mean == 0 ? 0 : Math.sqrt(variance) / mean;
		double sumSquares = used.stream().mapToDouble(c -> c.weight * c.weight).sum();
		double nEff = sumSquares > 0 ? 1 / sumSquares : 0;

		// 5. Data quality -----------------------------------------------------------------
		int transactions = (int) used.stream().filter(c -> c.record.kind() == Kind.TRANSACTION).count();
		int asking = used.size() - transactions;
		int userSupplied = (int) used.stream().filter(c -> c.userSupplied).count();
		List<Double> distances = used.stream().map(c -> c.record.distanceM()).filter(d -> d != null).sorted().toList();
		Double medianDistance = distances.isEmpty() ? null : median(distances);
		Double medianAge = used.isEmpty() ? null : median(used.stream().map(c -> c.monthsAgo).sorted().toList());
		Double coverage = used.isEmpty() ? null
				: used.stream().mapToInt(c -> c.attributesKnown).sum() / (double) (Adjustments.ATTRIBUTES * used.size());
		Double avgGross = used.isEmpty() ? null : used.stream().mapToDouble(c -> c.gross).average().orElse(0);
		boolean anyWithDistance = all.stream().anyMatch(c -> c.record.distanceM() != null);

		List<String> reasons = new ArrayList<>();
		if (!market.connected() && request.additionalComparables().isEmpty()) {
			reasons.add("No market database is connected, so there are no comparable properties to value this one against.");
		} else if (used.size() < MIN_COMPARABLES) {
			reasons.add(String.format(Locale.UK, "Only %d comparable %s passed the checks (%d found); at least %d are needed.",
					used.size(), used.size() == 1 ? "property" : "properties", all.size(), MIN_COMPARABLES));
		}
		if (used.size() >= MIN_COMPARABLES) {
			if (transactions == 0 && used.size() < MIN_ASKING_ONLY) {
				reasons.add(String.format(Locale.UK, "No completed %s and only %d asking prices: asking prices alone need at least %d records.",
						rent ? "lettings" : "sales", used.size(), MIN_ASKING_ONLY));
			}
			if (cv > MAX_DISPERSION) {
				reasons.add(String.format(Locale.UK, "Comparable prices disagree too much (±%.0f%% after adjustments) to support one range.", cv * 100));
			}
			if (nEff < MIN_EFFECTIVE_SAMPLE) {
				reasons.add("One or two records dominate the evidence; more comparables are needed.");
			}
		}
		boolean sufficient = reasons.isEmpty();

		int qualityScore = sufficient ? qualityScore(used.size(), transactions, medianAge, medianDistance, cv, coverage) : 0;
		String grade = !sufficient ? "insufficient" : qualityScore >= 75 ? "high" : qualityScore >= 50 ? "medium" : "low";

		List<String> warnings = new ArrayList<>();
		if (sufficient) {
			if (grade.equals("low")) warnings.add("Low data quality: treat the range as indicative only.");
			if (transactions == 0) {
				warnings.add(String.format(Locale.UK, "Based on asking prices only, each reduced by %.0f%%; no completed %s were found.",
						askingDiscount * 100, rent ? "lettings" : "sales"));
			}
			if (!anyWithDistance) warnings.add("Matched by district name, not distance: the property has no map location.");
			if (coverage != null && coverage < 0.6) {
				warnings.add("Many comparables do not record floor, condition, parking or energy class; those adjustments were skipped.");
			}
			if (monthlyTrend == null) {
				warnings.add("Too few transactions to measure the local price trend; older records count for less instead.");
			}
			if (userSupplied > 0) {
				warnings.add(userSupplied + " comparable(s) were entered by the user; unverified ones carry less weight.");
			}
		}

		List<String> nextSteps = new ArrayList<>();
		if (!sufficient || !grade.equals("high")) {
			nextSteps.add("Ask the agent for registered sale prices in the same building or street from the last two years.");
			nextSteps.add("Add comparable " + (rent ? "lettings" : "sales") + " you know of: address, size, price and date.");
			if (s.location() == null) nextSteps.add("Set the property's map location so distances can be measured.");
			if (!sufficient && !rent) nextSteps.add("Commission an independent valuation from a certified valuer before making an offer.");
		}

		Metrics metrics = new Metrics(market.connected(), all.size(), used.size(), all.size() - used.size(), transactions,
				asking, userSupplied, round(nEff, 1), anyWithDistance ? radius : null, round(medianDistance, 0),
				round(medianAge, 1), used.isEmpty() ? null : round(cv * 100, 1),
				coverage == null ? null : round(coverage * 100, 0), avgGross == null ? null : round(avgGross * 100, 1),
				monthlyTrend == null ? null : round(monthlyTrend * 100, 2));
		DataQuality quality = new DataQuality(grade, qualityScore, sufficient, reasons, warnings, nextSteps, metrics);

		// 6. Scores and concerns (they do not depend on market data) -----------------------
		List<Concern> concerns = concerns(request, s, today.getYear());
		Score conditionScore = Scores.condition(s, concerns, today.getYear());
		Score locationScore = Scores.location(market.connected() ? market.location(s) : java.util.Optional.empty());

		// 7. Estimate, repairs and negotiation ----------------------------------------------
		double askingPerSqm = round(s.askingPrice() / s.sizeSqm(), rent ? 1 : 0);
		Estimate estimate = null;
		String verdict = null;
		Double deltaPct = null;
		Negotiation negotiation = null;
		double halfWidth = 0;
		double conditionAllowance = 0;
		if (sufficient) {
			halfWidth = Math.clamp(0.025 + cv / Math.sqrt(nEff)
					+ (grade.equals("low") ? 0.03 : grade.equals("medium") ? 0.01 : 0)
					+ (transactions == 0 ? 0.02 : 0), 0.04, 0.15);
			double mid = roundTo(medianPerSqm * s.sizeSqm(), step);
			double low = roundTo(mid * (1 - halfWidth), step);
			double high = roundTo(mid * (1 + halfWidth), step);
			double perSqmStep = rent ? 0.1 : 10;
			estimate = new Estimate(low, mid, high, roundTo(medianPerSqm, perSqmStep),
					roundTo(low / s.sizeSqm(), perSqmStep), roundTo(high / s.sizeSqm(), perSqmStep));
			verdict = s.askingPrice() < low ? "below-market" : s.askingPrice() > high ? "above-market" : "fair";
			deltaPct = round((s.askingPrice() - mid) / mid * 100, 1);
			double conditionPct = Adjustments.CONDITION.get(s.condition());
			if (conditionPct < 0) conditionAllowance = mid / (1 + conditionPct) - mid;
		}
		Repairs repairs = repairs(request, conditionAllowance, step);
		if (sufficient) {
			negotiation = negotiation(s, estimate, verdict, deltaPct, repairs, used, concerns, monthlyTrend,
					halfWidth, transactions, asking, money, step);
		}

		List<ComparableResult> comparables = all.stream()
				.sorted(Comparator.comparing((Candidate c) -> c.excludedReason != null).thenComparing(c -> -c.weight))
				.map(c -> toResult(c, rent))
				.toList();

		return new ValuationReport(sufficient ? ValuationReport.VALUED : ValuationReport.INSUFFICIENT, ENGINE_ID,
				rent ? "rent" : "sale", clock.instant(), s.askingPrice(), askingPerSqm, estimate, verdict, deltaPct,
				conditionScore, locationScore, quality, comparables, concerns, repairs, negotiation, methodology(rent),
				"Estimate from comparable market records, for information only. It is not a formal valuation by a "
						+ "certified valuer, a building survey or financial advice.");
	}

	// Screening ------------------------------------------------------------------------------

	private static String screen(ValuationRequest.Subject s, Candidate c, boolean rent, LocalDate today) {
		MarketRecord r = c.record;
		// Database records are already filtered by listing type; user-supplied ones carry it in the source.
		boolean rentalRecord = c.userSupplied
				? r.source().endsWith(":rented") || r.source().endsWith(":rent-listing")
				: rent;
		if (rentalRecord != rent) return rent ? "Sale record: not comparable with a rent" : "Rental record: not comparable with a sale";
		if (!r.propertyType().equals(s.propertyType())) return "Different property type (" + r.propertyType() + ")";
		double ratio = r.sizeSqm() / s.sizeSqm();
		if (ratio < 1 - SIZE_TOLERANCE || ratio > 1 + SIZE_TOLERANCE) {
			return String.format(Locale.UK, "%.0f m²: outside ±%.0f%% of %.0f m²", r.sizeSqm(), SIZE_TOLERANCE * 100, s.sizeSqm());
		}
		if (r.observedOn().isAfter(today)) return "Dated in the future";
		if (c.monthsAgo > MAX_AGE_MONTHS) return String.format(Locale.UK, "%.0f months old (limit %d)", c.monthsAgo, MAX_AGE_MONTHS);
		if (r.distanceM() != null && r.distanceM() > RADII_M[RADII_M.length - 1]) {
			return String.format(Locale.UK, "%.0f m away (limit %.0f m)", r.distanceM(), RADII_M[RADII_M.length - 1]);
		}
		return null;
	}

	/** Smallest radius with enough evidence; records without a distance always count. */
	private static double chooseRadius(List<Candidate> all) {
		List<Candidate> active = active(all);
		long undistanced = active.stream().filter(c -> c.record.distanceM() == null).count();
		for (double r : RADII_M) {
			long within = active.stream().filter(c -> c.record.distanceM() != null && c.record.distanceM() <= r).count();
			if (within + undistanced >= TARGET_COMPARABLES) return r;
		}
		return RADII_M[RADII_M.length - 1];
	}

	private static void removeOutliers(List<Candidate> active) {
		if (active.size() < 5) return;
		List<Double> values = active.stream().map(c -> c.adjustedPerSqm).sorted().toList();
		double med = median(values);
		double mad = median(values.stream().map(v -> Math.abs(v - med)).sorted().toList());
		if (mad == 0) return;
		for (Candidate c : active) {
			double z = Math.abs(c.adjustedPerSqm - med) / (1.4826 * mad);
			// Both statistically and materially different: tight clusters must not shed 3 % deviations.
			if (z > OUTLIER_Z && Math.abs(c.adjustedPerSqm - med) / med > OUTLIER_MIN_DEVIATION) {
				c.excludedReason = String.format(Locale.UK, "Outlier: adjusted price per m² far from the other comparables (z = %.1f)", z);
			}
		}
	}

	/**
	 * Local price trend from transactions only: least squares of log price per m² on age in months.
	 * Needs six transactions spread over at least nine months; capped at ±1 % a month.
	 */
	private static Double applyTrend(List<Candidate> active) {
		List<Candidate> sales = active.stream().filter(c -> c.record.kind() == Kind.TRANSACTION).toList();
		if (sales.size() < 6) return null;
		double minAge = sales.stream().mapToDouble(c -> c.monthsAgo).min().orElse(0);
		double maxAge = sales.stream().mapToDouble(c -> c.monthsAgo).max().orElse(0);
		if (maxAge - minAge < 9) return null;
		double mx = sales.stream().mapToDouble(c -> c.monthsAgo).average().orElse(0);
		double my = sales.stream().mapToDouble(c -> Math.log(c.adjustedPerSqm)).average().orElse(0);
		double sxy = 0;
		double sxx = 0;
		for (Candidate c : sales) {
			sxy += (c.monthsAgo - mx) * (Math.log(c.adjustedPerSqm) - my);
			sxx += Math.pow(c.monthsAgo - mx, 2);
		}
		double monthly = Math.clamp(-sxy / sxx, -MAX_MONTHLY_TREND, MAX_MONTHLY_TREND);
		for (Candidate c : active) {
			double pct = Math.exp(monthly * c.monthsAgo) - 1;
			if (Math.abs(pct) < 0.002) continue;
			List<Adjustment> list = new ArrayList<>(c.adjustments);
			list.add(new Adjustment("Market movement", pct, String.format(Locale.UK,
					"%.0f months ago; local prices moved %+.2f%% a month", c.monthsAgo, monthly * 100)));
			c.adjustments = List.copyOf(list);
			c.adjustedPerSqm *= 1 + pct;
			c.gross += Math.abs(pct);
		}
		return monthly;
	}

	private static double weight(Candidate c, ValuationRequest.Subject s) {
		MarketRecord r = c.record;
		double evidence = r.kind() == Kind.TRANSACTION ? 1.0 : 0.7;
		if (c.userSupplied && !r.verified()) evidence *= 0.6;
		double distance = r.distanceM() == null ? 0.5 : 1 / (1 + r.distanceM() / 750);
		double recency = Math.exp(-c.monthsAgo / 18);
		double adjustment = 1 - 0.6 * Math.min(c.gross / MAX_GROSS_ADJUSTMENT, 1);
		double size = 1 - Math.abs(Math.log(r.sizeSqm() / s.sizeSqm()));
		return evidence * distance * recency * adjustment * Math.max(size, 0.1);
	}

	private static double weightedMedian(List<Candidate> used) {
		if (used.isEmpty()) return 0;
		List<Candidate> sorted = used.stream().sorted(Comparator.comparingDouble(c -> c.adjustedPerSqm)).toList();
		double cumulative = 0;
		for (int k = 0; k < sorted.size(); k++) {
			cumulative += sorted.get(k).weight;
			if (Math.abs(cumulative - 0.5) < 1e-9 && k + 1 < sorted.size()) {
				return (sorted.get(k).adjustedPerSqm + sorted.get(k + 1).adjustedPerSqm) / 2;
			}
			if (cumulative > 0.5) return sorted.get(k).adjustedPerSqm;
		}
		return sorted.getLast().adjustedPerSqm;
	}

	private static int qualityScore(int used, int transactions, Double medianAge, Double medianDistance, double cv, Double coverage) {
		double score = Math.min(used, 10) / 10.0 * 25
				+ (double) transactions / used * 20
				+ (medianAge == null ? 0 : 15 * linear(medianAge, 6, 30))
				+ (medianDistance == null ? 5 : 15 * linear(medianDistance, 500, 3000))
				+ 15 * linear(cv, 0.08, MAX_DISPERSION)
				+ (coverage == null ? 0 : 10 * coverage);
		return (int) Math.round(Math.clamp(score, 0, 100));
	}

	/** 1 at or below {@code good}, 0 at or above {@code bad}, linear in between. */
	private static double linear(double value, double good, double bad) {
		if (value <= good) return 1;
		if (value >= bad) return 0;
		return 1 - (value - good) / (bad - good);
	}

	// Concerns, repairs, negotiation ---------------------------------------------------------

	private static List<Concern> concerns(ValuationRequest request, ValuationRequest.Subject s, int currentYear) {
		List<Concern> list = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (ValuationRequest.Concern c : request.concerns()) {
			if (!seen.add(c.title().trim().toLowerCase(Locale.ROOT))) continue;
			list.add(new Concern(c.area(), c.title().trim(), Scores.normaliseSeverity(c.severity()), c.observed(),
					c.source() == null || c.source().isBlank() ? "user" : c.source()));
		}
		int age = currentYear - Adjustments.effectiveYear(s.yearBuilt(), s.renovatedYear());
		if (age > 40 && s.renovatedYear() == null) {
			list.add(new Concern("Building", String.format(Locale.UK,
					"Built in %d with no recorded renovation: wiring, plumbing and windows may be original", s.yearBuilt()),
					"medium", false, ENGINE_SOURCE));
		}
		if (Set.of("E", "F", "G").contains(s.energyClass())) {
			list.add(new Concern("Energy", "Energy class " + s.energyClass() + ": higher running costs and likely upgrade spending",
					"low", true, ENGINE_SOURCE));
		}
		if (s.floor() >= 3 && !s.elevator()) {
			list.add(new Concern("Access", "Floor " + s.floor() + " without an elevator narrows the pool of buyers and tenants",
					"low", true, ENGINE_SOURCE));
		}
		list.sort(Comparator.comparingInt(c -> SEVERITY_RANK.getOrDefault(c.severity(), 5)));
		return List.copyOf(list);
	}

	private static Repairs repairs(ValuationRequest request, double conditionAllowance, double step) {
		double vl = 0;
		double vh = 0;
		double ul = 0;
		double uh = 0;
		List<RepairLine> lines = new ArrayList<>();
		for (ValuationRequest.RepairEstimate r : request.repairEstimates()) {
			double low = Math.min(r.low(), r.high());
			double high = Math.max(r.low(), r.high());
			if (r.verified()) {
				vl += low;
				vh += high;
			} else {
				ul += low;
				uh += high;
			}
			lines.add(new RepairLine(r.item(), low, high, r.source(), r.verified()));
		}
		double verifiedMid = (vl + vh) / 2;
		double deducted = roundTo(Math.max(0, verifiedMid - conditionAllowance), step);
		return new Repairs(vl, vh, ul, uh, roundTo(conditionAllowance, step), deducted, List.copyOf(lines));
	}

	private static Negotiation negotiation(ValuationRequest.Subject s, Estimate e, String verdict, double deltaPct,
			Repairs repairs, List<Candidate> used, List<Concern> concerns, Double trend, double halfWidth,
			int transactions, int asking, Money money, double step) {
		double ask = s.askingPrice();
		double deduct = repairs.deductedFromOffer();
		double maxOffer;
		double target;
		double opening;
		if (verdict.equals("below-market") && deduct == 0) {
			maxOffer = ask;
			target = ask;
			opening = roundTo(ask * 0.97, step);
		} else {
			maxOffer = Math.min(ask, roundTo(e.high() - deduct, step));
			target = Math.min(maxOffer, Math.min(ask, roundTo(e.mid() - deduct, step)));
			opening = Math.min(target, Math.min(ask, roundTo(e.low() - deduct, step)));
		}
		opening = Math.max(opening, step);
		target = Math.max(target, opening);
		maxOffer = Math.max(maxOffer, target);

		List<String> evidence = new ArrayList<>();
		evidence.add(String.format(Locale.UK, "Weighted median of %d adjusted comparables (%d %s, %d asking %s): %s/m² × %.0f m² = %s.",
				used.size(), transactions, s.listingType().equals("rent") ? "lettings" : "sales", asking,
				asking == 1 ? "price" : "prices",
				money.perSqm(e.perSqm()), s.sizeSqm(), money.of(e.mid())));
		evidence.add(String.format(Locale.UK, "Fair value range %s to %s (±%.1f%%), set by how closely the comparables agree.",
				money.of(e.low()), money.of(e.high()), halfWidth * 100));
		used.stream().sorted(Comparator.comparingDouble((Candidate c) -> -c.weight)).limit(3).forEach(c -> evidence.add(
				String.format(Locale.UK, "%s %s%s: %.0f m² at %s (%s/m²), adjusted to %s/m² for this property.",
						c.record.kind() == Kind.TRANSACTION ? (s.listingType().equals("rent") ? "Let" : "Sold") : "Advertised",
						ago(c.monthsAgo),
						c.record.distanceM() == null ? "" : String.format(Locale.UK, ", %.0f m away", c.record.distanceM()),
						c.record.sizeSqm(), money.of(c.record.price()), money.perSqm(c.record.pricePerSqm()),
						money.perSqm(c.adjustedPerSqm))));
		double askingPerSqm = ask / s.sizeSqm();
		if (verdict.equals("above-market")) {
			evidence.add(String.format(Locale.UK, "The asking price is %s/m², %.1f%% above the estimated value and above the top of the range.",
					money.perSqm(askingPerSqm), deltaPct));
		} else if (verdict.equals("below-market")) {
			evidence.add(String.format(Locale.UK, "The asking price is %s/m², %.1f%% below the estimated value.", money.perSqm(askingPerSqm), -deltaPct));
		} else {
			evidence.add(String.format(Locale.UK, "The asking price is %s/m², within the fair value range (%+.1f%% from the midpoint).",
					money.perSqm(askingPerSqm), deltaPct));
		}
		if (repairs.verifiedHigh() > 0) {
			evidence.add(String.format(Locale.UK, "Verified repair estimates total %s to %s; the condition adjustment already accounts for about %s, so %s is taken off the offers.",
					money.of(repairs.verifiedLow()), money.of(repairs.verifiedHigh()), money.of(repairs.reflectedInValue()), money.of(deduct)));
		}
		List<String> unquoted = concerns.stream()
				.filter(c -> !c.severity().equals("info") && !c.source().equals(ENGINE_SOURCE))
				.map(Concern::title)
				.limit(4)
				.toList();
		if (repairs.unverifiedHigh() > 0 || (!unquoted.isEmpty() && repairs.verifiedHigh() == 0)) {
			evidence.add("Concerns without a written quote are not deducted: get quotes before using them in the negotiation"
					+ (unquoted.isEmpty() ? "." : " (" + String.join("; ", unquoted) + ")."));
		}
		if (trend != null) {
			evidence.add(String.format(Locale.UK, "Local transactions show prices moving %+.2f%% a month; older sales were adjusted for it.", trend * 100));
		}

		String repairsText = deduct > 0 ? String.format(Locale.UK, " and %s of verified repairs not already reflected in the value", money.of(deduct)) : "";
		String strategy = switch (verdict) {
			case "above-market" -> String.format(Locale.UK,
					"Open at %s, supported by the comparable %s below%s. Aim for %s and do not go above %s: the asking price is %.1f%% above the estimated value.",
					money.of(opening), s.listingType().equals("rent") ? "lettings" : "sales", repairsText, money.of(target), money.of(maxOffer), deltaPct);
			case "below-market" -> deduct > 0
					? String.format(Locale.UK, "The asking price is below the estimated range, but verified repairs justify an offer of %s; do not go above %s.",
							money.of(opening), money.of(maxOffer))
					: "The asking price is below the estimated range, so there is little room to negotiate. Find out why it is priced low (legal, structural or tenancy issues) before committing.";
			default -> String.format(Locale.UK,
					"The asking price is within the fair value range. Open at %s%s, aim for %s and treat %s as the maximum; negotiate on terms rather than paying above it.",
					money.of(opening), repairsText.isEmpty() ? "" : ", citing" + repairsText.replaceFirst(" and", ""), money.of(target), money.of(maxOffer));
		};
		return new Negotiation(opening, target, maxOffer, strategy, List.copyOf(evidence));
	}

	private static List<String> methodology(boolean rent) {
		return List.of(
				"Comparables: same property type and " + (rent ? "rental" : "sales") + " market, size within ±40%, observed in the last 36 months, within 1, 2 or 3 km (the smallest radius with enough evidence).",
				"Adjustments to each comparable's price per m²: asking-price margin (" + (rent ? "3" : "5") + "%), size, effective building age, condition, floor and elevator, parking, energy class, bathrooms, and the local price trend when at least six transactions span nine months.",
				"Records needing more than 35% gross adjustment and statistical outliers are excluded; the rest are weighted by evidence type, distance, recency, size similarity and adjustment size.",
				"Estimate = weighted median adjusted price per m² × size. The range widens with disagreement between comparables, a small sample and weaker evidence.",
				"No valuation is shown with fewer than 3 usable comparables, asking prices alone below 5 records, or a spread above ±30%.",
				"The condition and location scores are separate from the price; repair costs move the suggested offers only when verified, and only beyond what the condition adjustment already covers.");
	}

	// Helpers --------------------------------------------------------------------------------

	private static MarketRecord fromInput(ValuationRequest.ComparableInput c, int index) {
		return new MarketRecord("user-" + index, "user:" + c.listingType(), Kind.fromListingType(c.listingType()),
				c.address(), c.propertyType(), c.sizeSqm(), c.bedrooms(), c.bathrooms(), c.floor(), c.elevator(),
				c.parking(), c.yearBuilt(), c.renovatedYear(), c.condition(), c.energyClass(), c.price(), c.observedOn(),
				c.distanceM(), c.verified());
	}

	private static ComparableResult toResult(Candidate c, boolean rent) {
		MarketRecord r = c.record;
		boolean excluded = c.excludedReason != null;
		String listingType = switch (r.kind()) {
			case TRANSACTION -> rent ? "rented" : "sold";
			case ASKING -> rent ? "rent-listing" : "sale-listing";
		};
		if (c.userSupplied) listingType = r.source().substring("user:".length());
		boolean adjusted = !c.adjustments.isEmpty() || c.adjustedPerSqm > 0;
		return new ComparableResult(r.id(), c.userSupplied ? "user" : r.source(), listingType, r.address(), r.sizeSqm(),
				r.price(), round(r.pricePerSqm(), rent ? 2 : 0), r.observedOn(), r.distanceM(), r.verified(),
				c.adjustments.stream().map(a -> new Adjustment(a.factor(), round(a.pct() * 100, 1), a.detail())).toList(),
				adjusted ? round(c.adjustedPerSqm, rent ? 2 : 0) : null,
				adjusted ? round(c.gross * 100, 1) : null,
				excluded ? null : round(c.weight, 3),
				c.excludedReason);
	}

	private static List<Candidate> active(List<Candidate> all) {
		return all.stream().filter(c -> c.excludedReason == null).toList();
	}

	private static double median(List<Double> sorted) {
		int n = sorted.size();
		return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
	}

	static double roundTo(double value, double step) {
		return Math.round(value / step) * step;
	}

	private static Double round(Double value, int decimals) {
		if (value == null) return null;
		double f = Math.pow(10, decimals);
		return Math.round(value * f) / f;
	}

	private static String ago(double months) {
		if (months < 1) return "this month";
		long m = Math.round(months);
		return m == 1 ? "1 month ago" : m + " months ago";
	}

	/** Euro amounts, with "/month" for rents. */
	private record Money(boolean rent) {
		private static final NumberFormat FORMAT = NumberFormat.getIntegerInstance(Locale.UK);

		String of(double amount) {
			return "€" + FORMAT.format(Math.round(amount)) + (rent ? "/month" : "");
		}

		String perSqm(double amount) {
			return rent ? String.format(Locale.UK, "€%.1f", amount) : "€" + FORMAT.format(Math.round(amount));
		}
	}
}
