package com.propertyapp.common;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Optional access to the PostgreSQL + PostGIS database. Modules that read market or location
 * data ask for it and report "no data connected" when it is absent, instead of inventing figures.
 */
public record Database(Optional<JdbcTemplate> jdbc) {

	public static Database none() {
		return new Database(Optional.empty());
	}

	public boolean connected() {
		return jdbc.isPresent();
	}
}
