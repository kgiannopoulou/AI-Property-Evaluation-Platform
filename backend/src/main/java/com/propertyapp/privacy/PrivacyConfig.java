package com.propertyapp.privacy;

import com.propertyapp.audit.Audit;
import com.propertyapp.audit.AuditLog;
import com.propertyapp.common.Database;
import com.propertyapp.inspection.PhotoStorage;
import com.propertyapp.users.AccountService;
import com.propertyapp.users.AccountStore;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Configuration
@EnableScheduling
class PrivacyConfig {

	@Bean
	PrivacyStore privacyStore(Database database) {
		return database.jdbc().<PrivacyStore>map(JdbcPrivacyStore::new).orElseGet(InMemoryPrivacyStore::new);
	}

	@Bean
	ConsentService consentService(PrivacyStore store, Clock clock) {
		return new ConsentService(store, clock);
	}

	@Bean
	PrivacyStore.RetentionPolicy retentionPolicy(
			@Value("${app.retention.uploaded-file-days:180}") int files,
			@Value("${app.retention.audit-days:365}") int audit,
			@Value("${app.retention.usage-months:24}") int usage,
			@Value("${app.retention.billing-event-days:90}") int billingEvents,
			@Value("${app.retention.enquiry-months:24}") int enquiries,
			@Value("${app.retention.revoked-key-days:90}") int revokedKeys) {
		return new PrivacyStore.RetentionPolicy(files, audit, usage, billingEvents, enquiries, revokedKeys);
	}

	@Bean
	RetentionService retentionService(PrivacyStore store, PhotoStorage storage, PrivacyStore.RetentionPolicy policy, Audit audit,
			Clock clock) {
		return new RetentionService(store, storage, policy, audit, clock);
	}

	@Bean
	PrivacyService privacyService(AccountStore accounts, AccountService accountService, PrivacyStore store, ConsentService consents,
			AuditLog auditLog, Audit audit, PhotoStorage storage, Clock clock) {
		return new PrivacyService(accounts, accountService, store, consents, auditLog, audit, storage, clock);
	}

	@RestControllerAdvice
	static class Errors {

		@ExceptionHandler(ConsentService.ConsentRequired.class)
		ResponseEntity<ProblemDetail> consentRequired(ConsentService.ConsentRequired e) {
			ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
			p.setTitle("Consent required");
			p.setType(URI.create("https://property-evaluation.example/problems/consent-required"));
			p.setProperty("code", "consent-required");
			p.setProperty("purpose", e.purpose().id());
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(p);
		}

		@ExceptionHandler(ConsentService.ConsentException.class)
		ResponseEntity<ProblemDetail> consent(ConsentService.ConsentException e) {
			HttpStatus status = HttpStatus.valueOf(e.status());
			ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
			p.setTitle("Consent");
			p.setProperty("code", "consent");
			return ResponseEntity.status(status).body(p);
		}
	}
}
