package com.propertyapp.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** consent_records, stored_files and purge_expired_data() from V9. */
public class JdbcPrivacyStore implements PrivacyStore {

	private final JdbcTemplate jdbc;

	public JdbcPrivacyStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	private static final RowMapper<Consent> CONSENT = (rs, i) -> new Consent(rs.getString("purpose"), rs.getBoolean("granted"),
			rs.getString("policy_version"), rs.getString("source"), rs.getTimestamp("recorded_at").toInstant());

	private static final RowMapper<StoredFile> FILE = (rs, i) -> new StoredFile(rs.getString("storage_key"),
			rs.getObject("owner_user_id", UUID.class), rs.getObject("uploaded_by", UUID.class), rs.getString("property_ref"),
			rs.getString("kind"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getTimestamp("created_at").toInstant(),
			rs.getTimestamp("delete_after").toInstant());

	@Override
	public void recordConsent(UUID userId, Consent c) {
		jdbc.update("""
				INSERT INTO consent_records (user_id, purpose, granted, policy_version, source, recorded_at) VALUES (?, ?, ?, ?, ?, ?)
				""", userId, c.purpose(), c.granted(), c.policyVersion(), c.source(), Timestamp.from(c.recordedAt()));
	}

	@Override
	public List<Consent> consentHistory(UUID userId) {
		return jdbc.query("SELECT * FROM consent_records WHERE user_id = ? ORDER BY recorded_at DESC, id", CONSENT, userId);
	}

	@Override
	public void saveFile(StoredFile f) {
		jdbc.update("""
				INSERT INTO stored_files (storage_key, owner_user_id, uploaded_by, property_ref, kind, content_type, size_bytes,
				    created_at, delete_after)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", f.key(), f.ownerId(), f.uploadedBy(), f.propertyRef(), f.kind(), f.contentType(), f.sizeBytes(),
				Timestamp.from(f.createdAt()), Timestamp.from(f.deleteAfter()));
	}

	@Override
	public Optional<StoredFile> file(String key) {
		return jdbc.query("SELECT * FROM stored_files WHERE storage_key = ?", FILE, key).stream().findFirst();
	}

	@Override
	public List<StoredFile> filesOf(UUID ownerId) {
		return jdbc.query("SELECT * FROM stored_files WHERE owner_user_id = ? ORDER BY created_at DESC", FILE, ownerId);
	}

	@Override
	public List<StoredFile> expiredFiles(Instant now, int limit) {
		return jdbc.query("SELECT * FROM stored_files WHERE delete_after <= ? ORDER BY delete_after LIMIT ?", FILE,
				Timestamp.from(now), limit);
	}

	@Override
	public void deleteFileRecord(String key) {
		jdbc.update("DELETE FROM stored_files WHERE storage_key = ?", key);
	}

	@Override
	public Map<String, Long> purge(Instant now, RetentionPolicy p) {
		Map<String, Long> out = new LinkedHashMap<>();
		jdbc.query("SELECT * FROM purge_expired_data(?::timestamptz, ?::int, ?::int, ?::int, ?::int, ?::int)",
				rs -> {
					out.put(rs.getString("category"), rs.getLong("deleted"));
				}, Timestamp.from(now), p.auditDays(), p.usageMonths(), p.billingEventDays(), p.enquiryMonths(), p.revokedKeyDays());
		return out;
	}

	@Override
	public void forget(UUID userId) {
		// consent_records and stored_files rows go with the user (ON DELETE CASCADE).
	}
}
