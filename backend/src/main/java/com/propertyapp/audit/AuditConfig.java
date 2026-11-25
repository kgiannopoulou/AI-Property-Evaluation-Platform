package com.propertyapp.audit;

import com.propertyapp.common.Database;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AuditConfig {

	private static final Logger log = LoggerFactory.getLogger(AuditConfig.class);

	@Bean
	AuditLog auditLog(Database database) {
		return database.jdbc().<AuditLog>map(JdbcAuditLog::new).orElseGet(InMemoryAuditLog::new);
	}

	@Bean
	Audit audit(AuditLog auditLog, Clock clock, @Value("${app.audit.ip-salt:}") String salt) {
		if (salt.isBlank()) {
			log.warn("AUDIT_IP_SALT is not set: a random salt is used, so address hashes change on every restart.");
			salt = UUID.randomUUID().toString();
		}
		return new Audit(auditLog, clock, salt);
	}
}
