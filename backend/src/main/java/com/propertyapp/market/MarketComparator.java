package com.propertyapp.market;

import com.propertyapp.valuation.MarketDataSource;
import com.propertyapp.valuation.MarketRecord;
import com.propertyapp.valuation.ValuationRequest.GeoPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Market Comparator: the raw market around a property, without the valuation engine's adjustments.
 *
 * <p>Distribution of price per m² for completed deals and for asking prices, where the subject's
 * asking price sits in it, the gap between asking and achieved prices, and a quarterly median
 * series. With fewer than five records of a kind the statistics for that kind are withheld.
 */
public class MarketComparator {

	static final int MIN_FOR_STATS = 5;

	private final MarketDataSource market;

	public MarketComparator(MarketDataSource market) {
		this.market = market;
	}

	public record Request(
			@NotNull @Pattern(regexp = "sale|rent") String listingType,
			@NotNull @Pattern(regexp = "apartment|maisonette|detached-house|studio|penthouse") String propertyType,
			@DecimalMin("15") @DecimalMax("2000") double sizeSqm,
			@Positive Double askingPrice,
			@Valid GeoPoint location,
			@Size(max = 120) String district,
			@Min(250) @Max(5000) Integer radiusM,
			@Min(3) @Max(60) Integer months) {}

	public record Stats(int count, double p10, double p25, double median, double p75, double p90) {}

	public record Record(String id, String kind, String address, double sizeSqm, double price, double pricePerSqm,
			LocalDate observedOn, Double distanceM) {}

	public record Quarter(String quarter, int count, double medianPerSqm) {}

	public record Result(
			boolean marketDataConnected,
			int records,
			Stats transactions,
			Stats asking,
			Double subjectPerSqm,
			Integer subjectPercentile,
			Double askingToTransactionGapPct,
			List<Quarter> quarterly,
			List<Record> comparables,
			List<String> notes) {}

	public Result compare(Request r, LocalDate today) {
		boolean rent = r.listingType().equals("rent");
		int radius = r.radiusM() == null ? 1500 : r.radiusM();
		int months = r.months() == null ? 24 : r.months();
		List<String> notes = new ArrayList<>();
		if (!market.connected()) {
			notes.add("No market database is connected, so there is nothing to compare with.");
			return new Result(false, 0, null, null, perSqm(r), null, null, List.of(), List.of(), notes);
		}
		List<MarketRecord> found = market.comparables(new MarketDataSource.Search(r.location(), r.district(), r.propertyType(),
				rent ? List.of("rented", "rent-listing") : List.of("sold", "sale-listing"), r.sizeSqm(), 0.4, radius,
				today.minusMonths(months), 200));
		List<MarketRecord> deals = found.stream().filter(m -> m.kind() == MarketRecord.Kind.TRANSACTION).toList();
		List<MarketRecord> ads = found.stream().filter(m -> m.kind() == MarketRecord.Kind.ASKING).toList();

		Stats dealStats = stats(deals, rent);
		Stats adStats = stats(ads, rent);
		if (dealStats == null) notes.add(String.format(Locale.UK, "Only %d completed %s found: too few for statistics.", deals.size(), rent ? "lettings" : "sales"));
		if (adStats == null) notes.add(String.format(Locale.UK, "Only %d asking prices found: too few for statistics.", ads.size()));

		Double subject = perSqm(r);
		Integer percentile = null;
		List<MarketRecord> reference = dealStats != null ? deals : adStats != null ? ads : List.of();
		if (subject != null && !reference.isEmpty()) {
			long below = reference.stream().filter(m -> m.pricePerSqm() < subject).count();
			percentile = (int) Math.round(100.0 * below / reference.size());
			notes.add(String.format(Locale.UK, "The asking price per m² is higher than %d%% of %s.", percentile,
					dealStats != null ? "completed deals" : "asking prices (no completed deals to compare)"));
		}
		Double gap = dealStats != null && adStats != null ? round1((adStats.median() / dealStats.median() - 1) * 100) : null;
		if (gap != null) notes.add(String.format(Locale.UK, "Asking prices are %.1f%% %s completed deals here.", Math.abs(gap), gap >= 0 ? "above" : "below"));

		Map<String, List<MarketRecord>> byQuarter = deals.stream().collect(Collectors.groupingBy(
				m -> m.observedOn().getYear() + "-Q" + m.observedOn().get(IsoFields.QUARTER_OF_YEAR), TreeMap::new, Collectors.toList()));
		List<Quarter> quarterly = byQuarter.entrySet().stream()
				.filter(e -> e.getValue().size() >= 2)
				.map(e -> new Quarter(e.getKey(), e.getValue().size(), roundPerSqm(percentile(sorted(e.getValue()), 0.5), rent)))
				.toList();
		if (quarterly.size() < byQuarter.size()) notes.add("Quarters with a single deal are left out of the series.");

		List<Record> records = found.stream()
				.sorted(Comparator.comparing((MarketRecord m) -> m.distanceM() == null ? Double.MAX_VALUE : m.distanceM()))
				.limit(40)
				.map(m -> new Record(m.id(), m.kind() == MarketRecord.Kind.TRANSACTION ? (rent ? "rented" : "sold") : "asking",
						m.address(), m.sizeSqm(), m.price(), roundPerSqm(m.pricePerSqm(), rent), m.observedOn(), m.distanceM()))
				.toList();
		notes.add("Raw prices per m², not adjusted for condition, floor or age: use the valuation engine for a fair value.");
		return new Result(true, found.size(), dealStats, adStats, subject, percentile, gap, quarterly, records, notes);
	}

	private static Stats stats(List<MarketRecord> list, boolean rent) {
		if (list.size() < MIN_FOR_STATS) return null;
		List<Double> s = sorted(list);
		return new Stats(s.size(), roundPerSqm(percentile(s, 0.1), rent), roundPerSqm(percentile(s, 0.25), rent),
				roundPerSqm(percentile(s, 0.5), rent), roundPerSqm(percentile(s, 0.75), rent), roundPerSqm(percentile(s, 0.9), rent));
	}

	private static List<Double> sorted(List<MarketRecord> list) {
		return list.stream().map(MarketRecord::pricePerSqm).sorted().toList();
	}

	/** Linear-interpolation percentile of a sorted list. */
	static double percentile(List<Double> sorted, double p) {
		double index = p * (sorted.size() - 1);
		int lo = (int) Math.floor(index);
		int hi = (int) Math.ceil(index);
		return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (index - lo);
	}

	private static Double perSqm(Request r) {
		return r.askingPrice() == null ? null : roundPerSqm(r.askingPrice() / r.sizeSqm(), r.listingType().equals("rent"));
	}

	private static double roundPerSqm(double v, boolean rent) {
		return rent ? Math.round(v * 10) / 10.0 : Math.round(v);
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
