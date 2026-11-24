package com.propertyapp.valuation;

import com.propertyapp.common.Database;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ValuationConfig {

	@Bean
	MarketDataSource marketDataSource(Database database) {
		return database.jdbc().<MarketDataSource>map(JdbcMarketDataSource::new).orElseGet(MarketDataSource::none);
	}

	@Bean
	ValuationEngine valuationEngine(MarketDataSource market, Clock clock) {
		return new ValuationEngine(market, clock);
	}
}
