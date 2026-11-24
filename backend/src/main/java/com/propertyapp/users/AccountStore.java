package com.propertyapp.users;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for accounts, sessions, API keys, teams, subscriptions and metered usage.
 * {@link JdbcAccountStore} is used with a database; {@link InMemoryAccountStore} only for local
 * development without one and for tests (it forgets everything on restart).
 */
public interface AccountStore {

	record User(UUID id, String email, String passwordHash, String fullName, String role, Instant createdAt) {}

	/** Statuses active, trialing and past_due grant the plan; canceled falls back to Free. */
	record Subscription(
			UUID id, UUID userId, String planId, String status, String paymentStatus, String provider,
			String providerSubscription, String providerCustomer, Instant currentPeriodStart, Instant currentPeriodEnd,
			boolean cancelAtPeriodEnd, Integer analysesLimit, Integer seats) {

		public boolean grantsPlan() {
			return status.equals("active") || status.equals("trialing") || status.equals("past_due");
		}
	}

	record ApiKey(UUID id, UUID ownerId, String name, String prefix, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {}

	record Organisation(UUID id, UUID ownerId, String name) {}

	record Member(UUID userId, String email, String fullName, String role) {}

	/**
	 * @param allowed false when the request would exceed the limit (nothing was recorded)
	 * @param counted how many properties were newly counted (re-runs of a property this month count 0)
	 * @param used analyses used this month after the call
	 */
	record Usage(boolean allowed, int counted, int used, List<UUID> eventIds) {}

	record Enquiry(UUID userId, String company, String contactEmail, Integer seats, Integer monthlyAnalyses, String message) {}

	class EmailTakenException extends RuntimeException {}

	class AlreadyInTeamException extends RuntimeException {}

	User createUser(String email, String passwordHash, String fullName, String role);

	Optional<User> userByEmail(String email);

	Optional<User> userById(UUID id);

	void recordLogin(UUID userId, Instant at);

	void saveSession(String tokenHash, UUID userId, Instant expiresAt);

	Optional<UUID> sessionUser(String tokenHash, Instant now);

	void deleteSession(String tokenHash);

	ApiKey createApiKey(UUID ownerId, String name, String prefix, String keyHash);

	List<ApiKey> apiKeys(UUID ownerId);

	boolean revokeApiKey(UUID ownerId, UUID keyId, Instant at);

	/** The key's owner when the key exists and is not revoked; records the use. */
	Optional<UUID> apiKeyOwner(String keyHash, Instant now);

	Optional<Subscription> currentSubscription(UUID userId);

	Optional<Subscription> subscriptionByProviderId(String provider, String providerSubscription);

	/** Inserts (null id) or updates; returns the stored row. */
	Subscription saveSubscription(Subscription s);

	Organisation createOrganisation(UUID ownerId, String name);

	/** The team the user owns or belongs to. */
	Optional<Organisation> organisationOf(UUID userId);

	void addMember(UUID organisationId, UUID userId, String role);

	boolean removeMember(UUID organisationId, UUID userId);

	List<Member> members(UUID organisationId);

	/**
	 * Atomically counts the properties against the account's monthly limit: all of them or none.
	 * Properties already counted this month are not counted again.
	 */
	Usage consume(UUID accountId, UUID actorId, LocalDate period, List<String> propertyRefs, String endpoint, int limit);

	void releaseUsage(List<UUID> eventIds);

	int usage(UUID accountId, LocalDate period);

	/** False when the event was already processed. */
	boolean recordBillingEvent(String eventId, String type);

	void saveEnquiry(Enquiry enquiry);
}
