package com.propertyapp.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only log of security and privacy events: sign-ins, sign-in failures, API keys, team
 * changes, billing changes, consent decisions, data exports, erasures, file access and denied
 * access. Events never hold an email address, password, token or IP address: targets that would
 * identify a person are hashed, and the client address is a salted hash.
 */
public interface AuditLog {

	/**
	 * @param actorKind user, api-key, system or anonymous
	 * @param outcome success, denied or failure
	 */
	record Event(Instant at, UUID actorUserId, String actorKind, String action, String outcome, String target, String ipHash,
			Map<String, Object> details) {}

	void record(Event event);

	/** The user's own events, newest first (shown in their activity list and their data export). */
	List<Event> forUser(UUID userId, int limit);

	/** Erasure: events stay, without the actor (the database does this with ON DELETE SET NULL). */
	default void forget(UUID userId) {}
}
