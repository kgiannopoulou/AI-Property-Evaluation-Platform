package com.propertyapp.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.propertyapp.valuation.MarketDataSource.LocationEvidence;
import com.propertyapp.valuation.MarketRecord.Kind;
import com.propertyapp.valuation.ValuationRequest.Concern;
import com.propertyapp.valuation.ValuationRequest.GeoPoint;
import com.propertyapp.valuation.ValuationRequest.RepairEstimate;
import com.propertyapp.valuation.ValuationRequest.Subject;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ValuationEngineTest {

	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);
	static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

	/** 70 m² two-bed flat, second floor with elevator, built 1978, fair condition, class D, asking €220,000. */
	static Subject subject() {
		return new Subject("sale", "apartment", 70, 2, 1, 2, true, false, 1978, null, "fair", "D", 220_000,
				new GeoPoint(37.9665, 23.744), "Pangrati");
	}

	static int seq = 0;

	/** A record identical to the subject except size, price, age of the record and distance. */
	static MarketRecord sold(double size, double perSqm, int monthsAgo, double distance) {
		return record(Kind.TRANSACTION, size, perSqm, monthsAgo, distance);
	}

	static MarketRecord record(Kind kind, double size, double perSqm, int monthsAgo, double distance) {
		return new MarketRecord("c" + (++seq), "test", kind, null, "apartment", size, 2, 1, 2, true, false, 1978, null,
				"fair", "D", size * perSqm, TODAY.minusMonths(monthsAgo), distance, kind == Kind.TRANSACTION);
	}

	static MarketDataSource source(List<MarketRecord> records, LocationEvidence location) {
		return new MarketDataSource() {
			@Override
			public boolean connected() {
				return true;
			}

			@Override
			public List<MarketRecord> comparables(Search search) {
				return records;
			}

			@Override
			public Optional<LocationEvidence> location(Subject subject) {
				return Optional.ofNullable(location);
			}
		};
	}

	static ValuationReport value(List<MarketRecord> records, ValuationRequest request) {
		return new ValuationEngine(source(records, null), CLOCK).value(request);
	}

	static ValuationRequest request(Subject s) {
		return new ValuationRequest(s, null, null, null);
	}

	/** Eight consistent nearby sales around €2,900/m². */
	static List<MarketRecord> goodEvidence() {
		return List.of(
				sold(68, 2880, 2, 250), sold(72, 2950, 3, 400), sold(70, 2900, 4, 300), sold(75, 2850, 5, 600),
				sold(66, 2980, 6, 450), sold(71, 2870, 2, 700), sold(69, 2920, 7, 350), sold(73, 2890, 3, 500));
	}

	@Test
	void refusesWithoutMarketData() {
		ValuationReport r = new ValuationEngine(MarketDataSource.none(), CLOCK).value(request(subject()));

		assertThat(r.status()).isEqualTo(ValuationReport.INSUFFICIENT);
		assertThat(r.estimate()).isNull();
		assertThat(r.verdict()).isNull();
		assertThat(r.negotiation()).isNull();
		assertThat(r.dataQuality().grade()).isEqualTo("insufficient");
		assertThat(r.dataQuality().reasons()).singleElement().asString().contains("No market database");
		assertThat(r.dataQuality().nextSteps()).anyMatch(s -> s.contains("certified valuer"));
		// Scores that do not depend on prices are still reported.
		assertThat(r.conditionScore().score()).isNotNull();
		assertThat(r.locationScore().score()).isNull();
	}

	@Test
	void refusesWithTooFewComparables() {
		ValuationReport r = value(List.of(sold(70, 2900, 2, 200), sold(72, 2950, 3, 300)), request(subject()));

		assertThat(r.status()).isEqualTo(ValuationReport.INSUFFICIENT);
		assertThat(r.dataQuality().reasons()).anyMatch(s -> s.startsWith("Only 2 comparable properties"));
		assertThat(r.comparables()).hasSize(2);
	}

	@Test
	void refusesWhenComparablesDisagree() {
		List<MarketRecord> wild = List.of(sold(70, 1500, 2, 200), sold(70, 4200, 2, 200), sold(70, 2000, 3, 300),
				sold(70, 3900, 3, 300), sold(70, 2600, 4, 300));
		ValuationReport r = value(wild, request(subject()));

		assertThat(r.status()).isEqualTo(ValuationReport.INSUFFICIENT);
		assertThat(r.dataQuality().reasons()).anyMatch(s -> s.contains("disagree too much"));
	}

	@Test
	void valuesTheExamplePropertyFromConsistentSales() {
		ValuationReport r = value(goodEvidence(), request(subject()));

		assertThat(r.status()).isEqualTo(ValuationReport.VALUED);
		assertThat(r.engine()).isEqualTo("comparables-v1");
		// ≈ €2,900/m² × 70 m² ≈ €203,000; asking €220,000 is above the range.
		assertThat(r.estimate().mid()).isBetween(199_000.0, 207_000.0);
		assertThat(r.estimate().low()).isLessThan(r.estimate().mid());
		assertThat(r.estimate().high()).isLessThan(220_000.0);
		assertThat(r.estimate().low() % 1000).isZero();
		assertThat(r.verdict()).isEqualTo("above-market");
		assertThat(r.deltaPct()).isPositive();
		assertThat(r.dataQuality().grade()).isEqualTo("high");
		assertThat(r.dataQuality().metrics().used()).isEqualTo(8);
		assertThat(r.dataQuality().metrics().searchRadiusM()).isEqualTo(1000.0);

		var n = r.negotiation();
		assertThat(n.openingOffer()).isLessThanOrEqualTo(n.targetPrice());
		assertThat(n.targetPrice()).isLessThanOrEqualTo(n.maxOffer());
		assertThat(n.maxOffer()).isLessThanOrEqualTo(r.estimate().high());
		assertThat(n.evidence()).anyMatch(s -> s.startsWith("Weighted median of 8 adjusted comparables"));
		assertThat(n.strategy()).contains("do not go above");
	}

	@Test
	void adjustsForConditionParkingAndAskingPrices() {
		var c = new MarketRecord("x", "test", Kind.ASKING, null, "apartment", 70, 2, 1, 2, true, true, 1978, null,
				"good", "D", 210_000, TODAY, 100.0, false);
		Adjustments.Result r = Adjustments.adjust(subject(), c, 0.05);

		assertThat(r.adjustments()).extracting(ValuationReport.Adjustment::factor)
				.containsExactly("Asking price", "Condition", "Parking");
		// 0.95 (asking) × 0.94 (fair vs good) × 1/1.04 (no parking) ≈ 0.8587
		assertThat(r.factor()).isCloseTo(0.95 * 0.94 / 1.04, org.assertj.core.data.Offset.offset(1e-9));
		assertThat(r.attributesKnown()).isEqualTo(6);
	}

	@Test
	void skipsAdjustmentsForUnrecordedAttributes() {
		var c = new MarketRecord("x", "test", Kind.TRANSACTION, null, "apartment", 70, null, null, null, null, null,
				null, null, null, null, 210_000, TODAY, 100.0, true);
		Adjustments.Result r = Adjustments.adjust(subject(), c, 0.05);

		assertThat(r.adjustments()).isEmpty();
		assertThat(r.attributesKnown()).isZero();
	}

	@Test
	void screensOutUnsuitableRecords() {
		List<MarketRecord> records = new ArrayList<>(goodEvidence());
		records.add(new MarketRecord("maisonette", "test", Kind.TRANSACTION, null, "maisonette", 70, 2, 1, 2, true,
				false, 1978, null, "fair", "D", 200_000, TODAY.minusMonths(2), 200.0, true));
		records.add(sold(140, 2500, 2, 200));     // too large
		records.add(sold(70, 2900, 48, 200));     // too old
		records.add(sold(70, 2900, 2, 2500));     // further than needed
		records.add(sold(70, 6500, 2, 200));      // outlier
		ValuationReport r = value(records, request(subject()));

		assertThat(r.status()).isEqualTo(ValuationReport.VALUED);
		assertThat(r.comparables()).filteredOn(c -> c.excludedReason() != null)
				.extracting(ValuationReport.ComparableResult::excludedReason)
				.anyMatch(s -> s.startsWith("Different property type"))
				.anyMatch(s -> s.contains("outside ±40%"))
				.anyMatch(s -> s.contains("months old"))
				.anyMatch(s -> s.contains("enough evidence within 1000 m"))
				.anyMatch(s -> s.startsWith("Outlier"));
		assertThat(r.dataQuality().metrics().used()).isEqualTo(8);
	}

	@Test
	void askingPricesAloneNeedFiveRecords() {
		List<MarketRecord> four = List.of(record(Kind.ASKING, 70, 3050, 1, 200), record(Kind.ASKING, 72, 3000, 1, 300),
				record(Kind.ASKING, 68, 3100, 2, 300), record(Kind.ASKING, 71, 3020, 1, 400));
		assertThat(value(four, request(subject())).dataQuality().reasons()).anyMatch(s -> s.contains("asking prices alone"));

		List<MarketRecord> six = new ArrayList<>(four);
		six.add(record(Kind.ASKING, 69, 3080, 2, 500));
		six.add(record(Kind.ASKING, 73, 2990, 1, 450));
		ValuationReport r = value(six, request(subject()));
		assertThat(r.status()).isEqualTo(ValuationReport.VALUED);
		assertThat(r.dataQuality().warnings()).anyMatch(s -> s.startsWith("Based on asking prices only"));
		// The 5 % asking-price margin brings ~€3,040/m² down to ~€2,890/m².
		assertThat(r.estimate().perSqm()).isBetween(2850.0, 2930.0);
	}

	@Test
	void onlyVerifiedRepairsBeyondTheConditionDiscountMoveTheOffers() {
		var base = value(goodEvidence(), request(subject()));
		var unverified = value(goodEvidence(), new ValuationRequest(subject(), null,
				List.of(new RepairEstimate("Bathroom renovation", 20_000, 30_000, "Online estimate", false)), null));
		var verified = value(goodEvidence(), new ValuationRequest(subject(), null,
				List.of(new RepairEstimate("Bathroom renovation", 20_000, 30_000, "Contractor quote", true)), null));

		assertThat(unverified.negotiation().maxOffer()).isEqualTo(base.negotiation().maxOffer());
		assertThat(unverified.negotiation().evidence()).anyMatch(s -> s.startsWith("Concerns without a written quote"));
		// Fair condition is already −6 %: ≈ €13,000 of the €25,000 midpoint is in the value.
		Repairs r = new Repairs(verified.repairs());
		assertThat(r.reflected).isBetween(12_000.0, 14_000.0);
		assertThat(verified.repairs().deductedFromOffer()).isEqualTo(25_000 - verified.repairs().reflectedInValue());
		assertThat(verified.negotiation().maxOffer())
				.isEqualTo(base.negotiation().maxOffer() - verified.repairs().deductedFromOffer());
	}

	private record Repairs(double reflected) {
		Repairs(ValuationReport.Repairs r) {
			this(r.reflectedInValue());
		}
	}

	@Test
	void valuesRentalsAgainstRentalRecords() {
		Subject rent = new Subject("rent", "apartment", 70, 2, 1, 2, true, false, 1978, null, "fair", "D", 950,
				new GeoPoint(37.9665, 23.744), null);
		List<MarketRecord> lets = List.of(sold(70, 12.5, 1, 200), sold(72, 12.8, 2, 300), sold(68, 12.2, 3, 250),
				sold(71, 12.6, 2, 400), sold(69, 12.4, 4, 350));
		ValuationReport r = value(lets, request(rent));

		assertThat(r.mode()).isEqualTo("rent");
		assertThat(r.estimate().mid()).isBetween(850.0, 900.0);
		assertThat(r.estimate().mid() % 10).isZero();
		assertThat(r.verdict()).isEqualTo("above-market");
		assertThat(r.negotiation().strategy()).contains("/month");
	}

	@Test
	void userSuppliedComparablesCountButWeighLess() {
		var input = new ValuationRequest.ComparableInput("Same building, 3rd floor", "sold", "apartment", 70, 203_000,
				TODAY.minusMonths(1), 10.0, 2, 1, 2, true, false, 1978, null, "fair", "D", false);
		ValuationReport r = new ValuationEngine(MarketDataSource.none(), CLOCK)
				.value(new ValuationRequest(subject(), null, null, List.of(input, input, input)));

		assertThat(r.status()).isEqualTo(ValuationReport.VALUED);
		assertThat(r.dataQuality().metrics().userSupplied()).isEqualTo(3);
		assertThat(r.dataQuality().warnings()).anyMatch(s -> s.contains("entered by the user"));
		assertThat(r.comparables()).allMatch(c -> c.source().equals("user"));
	}

	@Test
	void conditionScoreFallsWithObservedIssues() {
		var clean = value(goodEvidence(), request(subject())).conditionScore().score();
		var withIssues = value(goodEvidence(), new ValuationRequest(subject(), List.of(
				new Concern("kitchen", "Older kitchen", "moderate", true, "ai-inspection"),
				new Concern("living-room", "Windows may need replacement", "moderate", false, "ai-inspection"),
				new Concern("bathroom", "Bathroom requires renovation", "major", true, "ai-inspection")), null, null));

		// 58 (fair) − 6.9 (48 years old) − 2 (class D) = 49.1; then −5 −2.5 (potential, half) −10 = 31.6
		assertThat(clean).isEqualTo(49);
		assertThat(withIssues.conditionScore().score()).isEqualTo(32);
		assertThat(withIssues.concerns()).first().extracting(ValuationReport.Concern::severity).isEqualTo("high");
	}

	@Test
	void locationScoreFromFacilitiesOrDistrict() {
		var facilities = new LocationEvidence(List.of(
				new LocationEvidence.Facility("metro", 1, 350), new LocationEvidence.Facility("supermarket", 2, 150),
				new LocationEvidence.Facility("pharmacy", 2, 120), new LocationEvidence.Facility("school", 1, 380),
				new LocationEvidence.Facility("park", 1, 900), new LocationEvidence.Facility("cafe", 6, 80),
				new LocationEvidence.Facility("hospital", 1, 1000)), 82, "Pangrati", "demo");
		var r = new ValuationEngine(source(goodEvidence(), facilities), CLOCK).value(request(subject()));
		// rail 25 + groceries 15 + pharmacy 10 + hospital 0 (at the radius) + schools 15 + park 15 × 100/600 + amenities 15 × 6/8 = 78.75
		assertThat(r.locationScore().score()).isEqualTo(79);
		assertThat(r.locationScore().items()).hasSize(7);

		var district = new LocationEvidence(List.of(), 70, "Pangrati", "demo");
		var d = new ValuationEngine(source(goodEvidence(), district), CLOCK).value(request(subject()));
		assertThat(d.locationScore().score()).isEqualTo(70);
		assertThat(d.locationScore().basis()).startsWith("District reference only");
	}
}
