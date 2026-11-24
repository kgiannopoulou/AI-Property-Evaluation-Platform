package com.propertyapp.negotiation;

import static org.assertj.core.api.Assertions.assertThat;

import com.propertyapp.building.BuildingAnalysis;
import com.propertyapp.documents.DocumentChecker;
import com.propertyapp.valuation.MarketDataSource;
import com.propertyapp.valuation.MarketRecord;
import com.propertyapp.valuation.ValuationEngine;
import com.propertyapp.valuation.ValuationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class NegotiationAssistantTest {

	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);
	static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

	public static ValuationRequest.Subject subject() {
		return new ValuationRequest.Subject("sale", "apartment", 70, 2, 1, 2, true, false, 1978, null, "fair", "D", 220_000,
				new ValuationRequest.GeoPoint(37.9665, 23.744), null);
	}

	public static MarketDataSource sales() {
		List<MarketRecord> rs = new java.util.ArrayList<>();
		double[] perSqm = {2880, 2950, 2900, 2850, 2980, 2870, 2920, 2890};
		for (int i = 0; i < perSqm.length; i++) {
			rs.add(new MarketRecord("s" + i, "test", MarketRecord.Kind.TRANSACTION, null, "apartment", 70, 2, 1, 2, true, false,
					1978, null, "fair", "D", 70 * perSqm[i], TODAY.minusMonths(2 + i % 4), 300.0 + i * 50, true));
		}
		return new MarketDataSource() {
			public boolean connected() {
				return true;
			}

			public List<MarketRecord> comparables(Search s) {
				return rs;
			}

			public Optional<LocationEvidence> location(ValuationRequest.Subject s) {
				return Optional.empty();
			}
		};
	}

	static final BuildingAnalysis.Request BUILDING = new BuildingAnalysis.Request(1978, 5, 10, 2, 70, "reinforced-concrete",
			null, null, 1, null, null, null, null, null, null, null, null, 60.0, false, null, "Facade repainting", 1_800.0, true, false);

	@Test
	void combinesValuationBuildingAndDocuments() {
		var docs = new DocumentChecker.Request("sale", null, new DocumentChecker.Declared(70.0, "D", 1978, null),
				List.of("Seller A"), List.of(new DocumentChecker.Document("title-deed", LocalDate.of(2010, 1, 1), null, "1", 70.0,
						null, List.of("Somebody Else"), null, null, null, null, null)));
		var r = new NegotiationAssistant(new ValuationEngine(sales(), CLOCK), CLOCK)
				.advise(new NegotiationAssistant.Request(new ValuationRequest(subject(), List.of(
						new ValuationRequest.Concern("bathroom", "Bathroom requires renovation", "major", true, "ai-inspection")), null, null),
						BUILDING, docs));

		assertThat(r.price()).isNotNull();
		assertThat(r.price().maxOffer()).isLessThan(220_000);
		assertThat(r.arguments()).extracting(NegotiationAssistant.Argument::source).contains("valuation", "inspection", "building");
		assertThat(r.arguments().getFirst().strength()).isEqualTo("strong");
		assertThat(r.conditions()).anyMatch(c -> c.startsWith("Seller pays for building works"))
				.anyMatch(c -> c.startsWith("Pay the deposit"));
		assertThat(r.walkAwaySignals()).anyMatch(w -> w.startsWith("The owners on the Title deed"));
	}

	@Test
	void noPriceGuidanceWithoutEnoughData() {
		var r = new NegotiationAssistant(new ValuationEngine(MarketDataSource.none(), CLOCK), CLOCK)
				.advise(new NegotiationAssistant.Request(new ValuationRequest(subject(), null, null, null), null, null));
		assertThat(r.price()).isNull();
		assertThat(r.priceNote()).startsWith("No price guidance: No market database");
		assertThat(r.strategy()).startsWith("Do not anchor");
		assertThat(r.conditions()).anyMatch(c -> c.startsWith("Commission an independent valuation"));
	}
}
