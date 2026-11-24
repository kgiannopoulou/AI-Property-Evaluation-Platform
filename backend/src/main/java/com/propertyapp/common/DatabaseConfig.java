package com.propertyapp.common;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Connects when app.database.url (DATABASE_URL) is set; otherwise every data-backed module says so. */
@Configuration
class DatabaseConfig {

	@Bean
	Database database(
			@Value("${app.database.url:}") String url,
			@Value("${app.database.user:}") String user,
			@Value("${app.database.password:}") String password) {
		if (url.isBlank()) return Database.none();
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(url);
		config.setUsername(user);
		config.setPassword(password);
		config.setMaximumPoolSize(10);
		config.setPoolName("property-db");
		// Start even when the database is briefly unavailable; requests fail until it is back.
		config.setInitializationFailTimeout(-1);
		return new Database(Optional.of(new JdbcTemplate(new HikariDataSource(config))));
	}
}
