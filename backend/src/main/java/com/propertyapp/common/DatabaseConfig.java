package com.propertyapp.common;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Connects when app.database.url (DATABASE_URL) is set; otherwise every data-backed module says so. */
@Configuration
class DatabaseConfig {

	@Bean
	Database database(
			@Value("${app.database.url:}") String url,
			@Value("${app.database.user:}") String user,
			@Value("${app.database.password:}") String password) {
		if (url.isBlank()) return Database.none();
		return new Database(Optional.of(new JdbcTemplate(new DriverManagerDataSource(url, user, password))));
	}
}
