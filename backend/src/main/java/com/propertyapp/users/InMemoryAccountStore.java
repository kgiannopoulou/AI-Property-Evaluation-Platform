package com.propertyapp.users;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Account store kept in memory: for local development without a database and for tests. Everything
 * is lost on restart, so the backend logs a warning when it is used.
 */
public class InMemoryAccountStore implements AccountStore {

	private record Session(UUID userId, Instant expiresAt) {}

	private record StoredKey(ApiKey key, String hash) {}

	private record Event(UUID id, UUID account, LocalDate period, String ref, UUID actor, String endpoint, Instant at) {}

	private final Map<UUID, User> users = new LinkedHashMap<>();
	private final Map<String, Session> sessions = new HashMap<>();
	private final Map<UUID, StoredKey> keys = new LinkedHashMap<>();
	private final Map<UUID, Subscription> subscriptions = new LinkedHashMap<>();
	private final Map<UUID, Organisation> organisations = new LinkedHashMap<>();
	private final Map<UUID, Map<UUID, String>> memberships = new HashMap<>();
	private final List<Event> events = new ArrayList<>();
	private final Set<String> billingEvents = new HashSet<>();
	private final List<Enquiry> enquiries = new ArrayList<>();

	@Override
	public synchronized User createUser(String email, String passwordHash, String fullName, String role) {
		if (userByEmail(email).isPresent()) throw new EmailTakenException();
		User u = new User(UUID.randomUUID(), email, passwordHash, fullName, role, Instant.now());
		users.put(u.id(), u);
		return u;
	}

	@Override
	public synchronized Optional<User> userByEmail(String email) {
		return users.values().stream().filter(u -> u.email().equalsIgnoreCase(email)).findFirst();
	}

	@Override
	public synchronized Optional<User> userById(UUID id) {
		return Optional.ofNullable(users.get(id));
	}

	@Override
	public void recordLogin(UUID userId, Instant at) {}

	@Override
	public synchronized void saveSession(String tokenHash, UUID userId, Instant expiresAt) {
		sessions.put(tokenHash, new Session(userId, expiresAt));
	}

	@Override
	public synchronized Optional<UUID> sessionUser(String tokenHash, Instant now) {
		Session s = sessions.get(tokenHash);
		return s == null || !s.expiresAt().isAfter(now) ? Optional.empty() : Optional.of(s.userId());
	}

	@Override
	public synchronized void deleteSession(String tokenHash) {
		sessions.remove(tokenHash);
	}

	@Override
	public synchronized ApiKey createApiKey(UUID ownerId, String name, String prefix, String keyHash) {
		ApiKey k = new ApiKey(UUID.randomUUID(), ownerId, name, prefix, Instant.now(), null, null);
		keys.put(k.id(), new StoredKey(k, keyHash));
		return k;
	}

	@Override
	public synchronized List<ApiKey> apiKeys(UUID ownerId) {
		return keys.values().stream().map(StoredKey::key).filter(k -> k.ownerId().equals(ownerId)).toList();
	}

	@Override
	public synchronized boolean revokeApiKey(UUID ownerId, UUID keyId, Instant at) {
		StoredKey s = keys.get(keyId);
		if (s == null || !s.key().ownerId().equals(ownerId) || s.key().revokedAt() != null) return false;
		ApiKey k = s.key();
		keys.put(keyId, new StoredKey(new ApiKey(k.id(), k.ownerId(), k.name(), k.prefix(), k.createdAt(), k.lastUsedAt(), at), s.hash()));
		return true;
	}

	@Override
	public synchronized Optional<UUID> apiKeyOwner(String keyHash, Instant now) {
		for (var e : keys.entrySet()) {
			ApiKey k = e.getValue().key();
			if (e.getValue().hash().equals(keyHash) && k.revokedAt() == null) {
				e.setValue(new StoredKey(new ApiKey(k.id(), k.ownerId(), k.name(), k.prefix(), k.createdAt(), now, null), keyHash));
				return Optional.of(k.ownerId());
			}
		}
		return Optional.empty();
	}

	@Override
	public synchronized Optional<Subscription> currentSubscription(UUID userId) {
		return subscriptions.values().stream().filter(s -> s.userId().equals(userId) && s.grantsPlan()).findFirst();
	}

	@Override
	public synchronized Optional<Subscription> subscriptionByProviderId(String provider, String providerSubscription) {
		return subscriptions.values().stream()
				.filter(s -> provider.equals(s.provider()) && providerSubscription.equals(s.providerSubscription()))
				.findFirst();
	}

	@Override
	public synchronized Subscription saveSubscription(Subscription s) {
		Subscription stored = s.id() != null ? s : new Subscription(UUID.randomUUID(), s.userId(), s.planId(), s.status(),
				s.paymentStatus(), s.provider(), s.providerSubscription(), s.providerCustomer(), s.currentPeriodStart(),
				s.currentPeriodEnd(), s.cancelAtPeriodEnd(), s.analysesLimit(), s.seats());
		if (stored.grantsPlan()) {
			boolean other = subscriptions.values().stream()
					.anyMatch(x -> x.userId().equals(stored.userId()) && x.grantsPlan() && !x.id().equals(stored.id()));
			if (other) throw new IllegalStateException("The user already has an active subscription");
		}
		subscriptions.put(stored.id(), stored);
		return stored;
	}

	@Override
	public synchronized Organisation createOrganisation(UUID ownerId, String name) {
		if (organisationOf(ownerId).isPresent()) throw new AlreadyInTeamException();
		Organisation o = new Organisation(UUID.randomUUID(), ownerId, name);
		organisations.put(o.id(), o);
		memberships.put(o.id(), new LinkedHashMap<>());
		return o;
	}

	@Override
	public synchronized Optional<Organisation> organisationOf(UUID userId) {
		for (Organisation o : organisations.values()) {
			if (o.ownerId().equals(userId) || memberships.get(o.id()).containsKey(userId)) return Optional.of(o);
		}
		return Optional.empty();
	}

	@Override
	public synchronized void addMember(UUID organisationId, UUID userId, String role) {
		if (organisationOf(userId).isPresent()) throw new AlreadyInTeamException();
		memberships.get(organisationId).put(userId, role);
	}

	@Override
	public synchronized boolean removeMember(UUID organisationId, UUID userId) {
		return memberships.getOrDefault(organisationId, Map.of()).remove(userId) != null;
	}

	@Override
	public synchronized List<Member> members(UUID organisationId) {
		List<Member> out = new ArrayList<>();
		Organisation o = organisations.get(organisationId);
		User owner = users.get(o.ownerId());
		out.add(new Member(owner.id(), owner.email(), owner.fullName(), "owner"));
		memberships.get(organisationId).forEach((id, role) -> {
			User u = users.get(id);
			out.add(new Member(u.id(), u.email(), u.fullName(), role));
		});
		return out;
	}

	@Override
	public synchronized Usage consume(UUID accountId, UUID actorId, LocalDate period, List<String> propertyRefs, String endpoint, int limit) {
		Set<String> seen = new HashSet<>();
		for (Event e : events) if (e.account().equals(accountId) && e.period().equals(period)) seen.add(e.ref());
		List<String> fresh = propertyRefs.stream().distinct().filter(r -> !seen.contains(r)).toList();
		if (seen.size() + fresh.size() > limit) return new Usage(false, 0, seen.size(), List.of());
		List<UUID> ids = new ArrayList<>();
		for (String ref : fresh) {
			Event e = new Event(UUID.randomUUID(), accountId, period, ref, actorId, endpoint, Instant.now());
			events.add(e);
			ids.add(e.id());
		}
		return new Usage(true, fresh.size(), seen.size() + fresh.size(), ids);
	}

	@Override
	public synchronized void releaseUsage(List<UUID> eventIds) {
		events.removeIf(e -> eventIds.contains(e.id()));
	}

	@Override
	public synchronized int usage(UUID accountId, LocalDate period) {
		return (int) events.stream().filter(e -> e.account().equals(accountId) && e.period().equals(period)).count();
	}

	@Override
	public synchronized boolean recordBillingEvent(String eventId, String type) {
		return billingEvents.add(eventId);
	}

	@Override
	public synchronized void saveEnquiry(Enquiry enquiry) {
		enquiries.add(enquiry);
	}

	/** For tests. */
	public synchronized List<Enquiry> enquiries() {
		return List.copyOf(enquiries);
	}

	@Override
	public synchronized List<UsageEvent> usageEvents(UUID userId) {
		return events.stream().filter(e -> userId.equals(e.account()) || userId.equals(e.actor()))
				.sorted(java.util.Comparator.comparing(Event::at).reversed())
				.map(e -> new UsageEvent(e.period(), e.ref(), e.endpoint(), e.account(), e.actor(), e.at())).toList();
	}

	@Override
	public synchronized void deleteUser(UUID userId) {
		users.remove(userId);
		sessions.values().removeIf(s -> s.userId().equals(userId));
		keys.values().removeIf(k -> k.key().ownerId().equals(userId));
		subscriptions.values().removeIf(s -> s.userId().equals(userId));
		organisations.values().stream().filter(o -> o.ownerId().equals(userId)).map(Organisation::id).toList()
				.forEach(id -> {
					organisations.remove(id);
					memberships.remove(id);
				});
		memberships.values().forEach(m -> m.remove(userId));
		events.removeIf(e -> e.account().equals(userId));
		events.replaceAll(e -> userId.equals(e.actor()) ? new Event(e.id(), e.account(), e.period(), e.ref(), null, e.endpoint(), e.at()) : e);
		enquiries.removeIf(e -> userId.equals(e.userId()));
	}
}
