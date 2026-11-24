package com.propertyapp.market;

import com.propertyapp.valuation.MarketDataSource;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Market Comparator: similar properties and market price comparisons. */
@RestController
class MarketController {

	private final MarketComparator comparator;
	private final Clock clock;

	MarketController(MarketComparator comparator, Clock clock) {
		this.comparator = comparator;
		this.clock = clock;
	}

	@PostMapping("/api/v1/market")
	MarketComparator.Result handle(@Valid @RequestBody MarketComparator.Request request) {
		return comparator.compare(request, LocalDate.now(clock));
	}

	@Configuration
	static class Config {
		@Bean
		MarketComparator marketComparator(MarketDataSource market) {
			return new MarketComparator(market);
		}
	}
}
