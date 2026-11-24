package com.propertyapp.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.propertyapp.valuation.MarketDataSource;
import com.propertyapp.valuation.MarketRecord;
import com.propertyapp.valuation.MarketRecord.Kind;
import com.propertyapp.valuation.ValuationRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarketComparatorTest {

	static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

	static MarketRecord rec(Kind kind, double perSqm, LocalDate on) {
		return new MarketRecord("r" + perSqm + kind, "test", kind, null, "apartment", 70, 2, 1, 2, true, false, 1978, null,
				"fair", "D", 70 * perSqm, on, 300.0, false);
	}

	static MarketDataSource source(List<MarketRecord> records) {
		return new MarketDataSource() {
			public boolean connected() {
				return true;
			}

			public List<MarketRecord> comparables(Search search) {
				return records;
			}

			public Optional<LocationEvidence> location(ValuationRequest.Subject subject) {
				return Optional.empty();
			}
		};
	}

	static MarketComparator.Request request(Double asking) {
		return new MarketComparator.Request("sale", "apartment", 70, asking, new ValuationRequest.GeoPoint(37.96, 23.74), null, null, null);
	}

	@Test
	void distributionPositionGapAndTrend() {
		List<MarketRecord> records = new ArrayList<>();
		double[] deals = {2700, 2800, 2900, 3000, 3100, 3200};
		for (int i = 0; i < deals.length; i++) records.add(rec(Kind.TRANSACTION, deals[i], LocalDate.of(2026, i < 3 ? 2 : 8, 10)));
		for (double a : new double[] {3100, 3150, 3200, 3250, 3300}) records.add(rec(Kind.ASKING, a, TODAY.minusDays(20)));

		var r = new MarketComparator(source(records)).compare(request(3_000 * 70.0), TODAY);
		assertThat(r.transactions().count()).isEqualTo(6);
		assertThat(r.transactions().median()).isEqualTo(2950);
		assertThat(r.asking().median()).isEqualTo(3200);
		assertThat(r.subjectPerSqm()).isEqualTo(3000);
		assertThat(r.subjectPercentile()).isEqualTo(50); // 3 of 6 deals are cheaper per m²
		assertThat(r.askingToTransactionGapPct()).isEqualTo(8.5);
		assertThat(r.quarterly()).extracting(MarketComparator.Quarter::quarter).containsExactly("2026-Q1", "2026-Q3");
		assertThat(r.comparables()).hasSize(11);
	}

	@Test
	void withholdsStatisticsOnThinDataAndSaysWhenDisconnected() {
		var thin = new MarketComparator(source(List.of(rec(Kind.TRANSACTION, 3000, TODAY)))).compare(request(null), TODAY);
		assertThat(thin.transactions()).isNull();
		assertThat(thin.notes()).anyMatch(n -> n.startsWith("Only 1 completed sales"));

		var none = new MarketComparator(MarketDataSource.none()).compare(request(200_000.0), TODAY);
		assertThat(none.marketDataConnected()).isFalse();
		assertThat(none.comparables()).isEmpty();
	}

	@Test
	void percentileInterpolates() {
		assertThat(MarketComparator.percentile(List.of(1.0, 2.0, 3.0, 4.0), 0.5)).isEqualTo(2.5);
		assertThat(MarketComparator.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 0.25)).isEqualTo(2.0);
	}
}
