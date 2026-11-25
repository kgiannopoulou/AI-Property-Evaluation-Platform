package com.propertyapp.privacy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consent history, uploaded-file records and the retention purge. {@link JdbcPrivacyStore} uses the
 * V9 tables; {@link InMemoryPrivacyStore} is for development without a database and for tests.
 */
public interface PrivacyStore {

	record Consent(String purpose, boolean granted, String policyVersion, String source, Instant recordedAt) {}

	/** @param ownerId the account the file belongs to (the team owner for team uploads) */
	record StoredFile(String key, UUID ownerId, UUID uploadedBy, String propertyRef, String kind, String contentType, long sizeBytes,
			Instant createdAt, Instant deleteAfter) {}

	/** How long data is kept. Defaults are in application.yml and shown on the privacy notice. */
	record RetentionPolicy(int uploadedFileDays, int auditDays, int usageMonths, int billingEventDays, int enquiryMonths,
			int revokedKeyDays) {}

	void recordConsent(UUID userId, Consent consent);

	/** Newest first. */
	List<Consent> consentHistory(UUID userId);

	void saveFile(StoredFile file);

	Optional<StoredFile> file(String key);

	List<StoredFile> filesOf(UUID ownerId);

	List<StoredFile> expiredFiles(Instant now, int limit);

	void deleteFileRecord(String key);

	/** Deletes expired sessions, revoked keys, old usage, billing events, enquiries and audit events; counts per category. */
	Map<String, Long> purge(Instant now, RetentionPolicy policy);

	/** Erasure of the records this store keeps for the user (the database does it by cascade). */
	void forget(UUID userId);
}
