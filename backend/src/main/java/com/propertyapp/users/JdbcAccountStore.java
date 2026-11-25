package com.propertyapp.users;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL account store on the V1/V2/V8 tables. */
public class JdbcAccountStore implements AccountStore {

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;

	public JdbcAccountStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
	}

	private static final RowMapper<User> USER = (rs, i) -> new User(rs.getObject("id", UUID.class), rs.getString("email"),
			rs.getString("password_hash"), rs.getString("full_name"), rs.getString("role"), instant(rs, "created_at"));

	private static final RowMapper<Subscription> SUBSCRIPTION = (rs, i) -> new Subscription(rs.getObject("id", UUID.class),
			rs.getObject("user_id", UUID.class), rs.getString("plan_id"), rs.getString("status"), rs.getString("payment_status"),
			rs.getString("provider"), rs.getString("provider_subscription"), rs.getString("provider_customer"),
			instant(rs, "current_period_start"), instant(rs, "current_period_end"), rs.getBoolean("cancel_at_period_end"),
			rs.getObject("analyses_limit", Integer.class), rs.getObject("seats", Integer.class));

	private static final RowMapper<ApiKey> KEY = (rs, i) -> new ApiKey(rs.getObject("id", UUID.class),
			rs.getObject("owner_user_id", UUID.class), rs.getString("name"), rs.getString("prefix"), instant(rs, "created_at"),
			instant(rs, "last_used_at"), instant(rs, "revoked_at"));

	private static final RowMapper<Organisation> ORG = (rs, i) -> new Organisation(rs.getObject("id", UUID.class),
			rs.getObject("owner_user_id", UUID.class), rs.getString("name"));

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

	private static Timestamp ts(Instant i) {
		return i == null ? null : Timestamp.from(i);
	}

	@Override
	public User createUser(String email, String passwordHash, String fullName, String role) {
		try {
			return jdbc.queryForObject("""
					INSERT INTO users (email, password_hash, full_name, role) VALUES (?, ?, ?, ?) RETURNING *
					""", USER, email, passwordHash, fullName, role);
		} catch (DuplicateKeyException e) {
			throw new EmailTakenException();
		}
	}

	@Override
	public Optional<User> userByEmail(String email) {
		return jdbc.query("SELECT * FROM users WHERE lower(email) = lower(?)", USER, email).stream().findFirst();
	}

	@Override
	public Optional<User> userById(UUID id) {
		return jdbc.query("SELECT * FROM users WHERE id = ?", USER, id).stream().findFirst();
	}

	@Override
	public void recordLogin(UUID userId, Instant at) {
		jdbc.update("UPDATE users SET last_login_at = ? WHERE id = ?", ts(at), userId);
	}

	@Override
	public void saveSession(String tokenHash, UUID userId, Instant expiresAt) {
		jdbc.update("INSERT INTO user_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)", tokenHash, userId, ts(expiresAt));
		// Housekeeping: expired sessions of this user.
		jdbc.update("DELETE FROM user_sessions WHERE user_id = ? AND expires_at < now()", userId);
	}

	@Override
	public Optional<UUID> sessionUser(String tokenHash, Instant now) {
		return jdbc.query("""
				UPDATE user_sessions SET last_used_at = ? WHERE token_hash = ? AND expires_at > ? RETURNING user_id
				""", (rs, i) -> rs.getObject(1, UUID.class), ts(now), tokenHash, ts(now)).stream().findFirst();
	}

	@Override
	public void deleteSession(String tokenHash) {
		jdbc.update("DELETE FROM user_sessions WHERE token_hash = ?", tokenHash);
	}

	@Override
	public ApiKey createApiKey(UUID ownerId, String name, String prefix, String keyHash) {
		return jdbc.queryForObject("""
				INSERT INTO api_keys (owner_user_id, name, prefix, key_hash) VALUES (?, ?, ?, ?) RETURNING *
				""", KEY, ownerId, name, prefix, keyHash);
	}

	@Override
	public List<ApiKey> apiKeys(UUID ownerId) {
		return jdbc.query("SELECT * FROM api_keys WHERE owner_user_id = ? ORDER BY created_at DESC", KEY, ownerId);
	}

	@Override
	public boolean revokeApiKey(UUID ownerId, UUID keyId, Instant at) {
		return jdbc.update("UPDATE api_keys SET revoked_at = ? WHERE id = ? AND owner_user_id = ? AND revoked_at IS NULL",
				ts(at), keyId, ownerId) == 1;
	}

	@Override
	public Optional<UUID> apiKeyOwner(String keyHash, Instant now) {
		return jdbc.query("""
				UPDATE api_keys SET last_used_at = ? WHERE key_hash = ? AND revoked_at IS NULL RETURNING owner_user_id
				""", (rs, i) -> rs.getObject(1, UUID.class), ts(now), keyHash).stream().findFirst();
	}

	@Override
	public Optional<Subscription> currentSubscription(UUID userId) {
		return jdbc.query("""
				SELECT * FROM subscriptions WHERE user_id = ? AND status IN ('active', 'trialing', 'past_due')
				ORDER BY created_at DESC LIMIT 1
				""", SUBSCRIPTION, userId).stream().findFirst();
	}

	@Override
	public Optional<Subscription> subscriptionByProviderId(String provider, String providerSubscription) {
		return jdbc.query("SELECT * FROM subscriptions WHERE provider = ? AND provider_subscription = ?",
				SUBSCRIPTION, provider, providerSubscription).stream().findFirst();
	}

	@Override
	public Subscription saveSubscription(Subscription s) {
		if (s.id() == null) {
			return jdbc.queryForObject("""
					INSERT INTO subscriptions (user_id, plan_id, status, payment_status, provider, provider_subscription,
					    provider_customer, current_period_start, current_period_end, cancel_at_period_end, analyses_limit, seats,
					    canceled_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'canceled' THEN now() END) RETURNING *
					""", SUBSCRIPTION, s.userId(), s.planId(), s.status(), s.paymentStatus(), s.provider(), s.providerSubscription(),
					s.providerCustomer(), ts(s.currentPeriodStart()), ts(s.currentPeriodEnd()), s.cancelAtPeriodEnd(),
					s.analysesLimit(), s.seats(), s.status());
		}
		return jdbc.queryForObject("""
				UPDATE subscriptions SET plan_id = ?, status = ?, payment_status = ?, provider = ?, provider_subscription = ?,
				    provider_customer = ?, current_period_start = ?, current_period_end = ?, cancel_at_period_end = ?,
				    analyses_limit = ?, seats = ?,
				    canceled_at = CASE WHEN ? = 'canceled' THEN coalesce(canceled_at, now()) END,
				    last_payment_at = CASE WHEN ? = 'paid' AND payment_status <> 'paid' THEN now() ELSE last_payment_at END
				WHERE id = ? RETURNING *
				""", SUBSCRIPTION, s.planId(), s.status(), s.paymentStatus(), s.provider(), s.providerSubscription(),
				s.providerCustomer(), ts(s.currentPeriodStart()), ts(s.currentPeriodEnd()), s.cancelAtPeriodEnd(),
				s.analysesLimit(), s.seats(), s.status(), s.paymentStatus(), s.id());
	}

	@Override
	public Organisation createOrganisation(UUID ownerId, String name) {
		if (organisationOf(ownerId).isPresent()) throw new AlreadyInTeamException();
		return jdbc.queryForObject("INSERT INTO organisations (owner_user_id, name) VALUES (?, ?) RETURNING *", ORG, ownerId, name);
	}

	@Override
	public Optional<Organisation> organisationOf(UUID userId) {
		return jdbc.query("""
				SELECT o.* FROM organisations o
				WHERE o.owner_user_id = ?
				   OR o.id = (SELECT organisation_id FROM organisation_members WHERE user_id = ?)
				LIMIT 1
				""", ORG, userId, userId).stream().findFirst();
	}

	@Override
	public void addMember(UUID organisationId, UUID userId, String role) {
		if (organisationOf(userId).isPresent()) throw new AlreadyInTeamException();
		try {
			jdbc.update("INSERT INTO organisation_members (organisation_id, user_id, role) VALUES (?, ?, ?)", organisationId, userId, role);
		} catch (DuplicateKeyException e) {
			throw new AlreadyInTeamException();
		}
	}

	@Override
	public boolean removeMember(UUID organisationId, UUID userId) {
		return jdbc.update("DELETE FROM organisation_members WHERE organisation_id = ? AND user_id = ?", organisationId, userId) == 1;
	}

	@Override
	public List<Member> members(UUID organisationId) {
		return jdbc.query("""
				SELECT u.id, u.email, u.full_name, 'owner' AS role, 0 AS ord FROM organisations o JOIN users u ON u.id = o.owner_user_id
				WHERE o.id = ?
				UNION ALL
				SELECT u.id, u.email, u.full_name, m.role, 1 FROM organisation_members m JOIN users u ON u.id = m.user_id
				WHERE m.organisation_id = ?
				ORDER BY ord, email
				""", (rs, i) -> new Member(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"),
				rs.getString("role")), organisationId, organisationId);
	}

	@Override
	public Usage consume(UUID accountId, UUID actorId, LocalDate period, List<String> propertyRefs, String endpoint, int limit) {
		return tx.execute(status -> {
			// Serialise concurrent requests of one account: the limit check and the insert are one step.
			jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE", rs -> {}, accountId);
			Set<String> seen = new HashSet<>(jdbc.queryForList(
					"SELECT property_ref FROM usage_events WHERE account_user_id = ? AND period = ?", String.class, accountId, period));
			List<String> fresh = propertyRefs.stream().distinct().filter(r -> !seen.contains(r)).toList();
			if (seen.size() + fresh.size() > limit) return new Usage(false, 0, seen.size(), List.of());
			List<UUID> ids = new ArrayList<>();
			for (String ref : fresh) {
				ids.add(jdbc.queryForObject("""
						INSERT INTO usage_events (account_user_id, actor_user_id, period, property_ref, endpoint)
						VALUES (?, ?, ?, ?, ?) RETURNING id
						""", UUID.class, accountId, actorId, period, ref, endpoint));
			}
			return new Usage(true, fresh.size(), seen.size() + fresh.size(), ids);
		});
	}

	@Override
	public void releaseUsage(List<UUID> eventIds) {
		for (UUID id : eventIds) jdbc.update("DELETE FROM usage_events WHERE id = ?", id);
	}

	@Override
	public int usage(UUID accountId, LocalDate period) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM usage_events WHERE account_user_id = ? AND period = ?",
				Integer.class, accountId, period);
		return n == null ? 0 : n;
	}

	@Override
	public boolean recordBillingEvent(String eventId, String type) {
		return jdbc.update("INSERT INTO billing_events (event_id, type) VALUES (?, ?) ON CONFLICT DO NOTHING", eventId, type) == 1;
	}

	@Override
	public void saveEnquiry(Enquiry e) {
		jdbc.update("""
				INSERT INTO business_enquiries (user_id, company, contact_email, seats, monthly_analyses, message)
				VALUES (?, ?, ?, ?, ?, ?)
				""", e.userId(), e.company(), e.contactEmail(), e.seats(), e.monthlyAnalyses(), e.message());
	}

	@Override
	public List<UsageEvent> usageEvents(UUID userId) {
		return jdbc.query("""
				SELECT period, property_ref, endpoint, account_user_id, actor_user_id, created_at FROM usage_events
				WHERE account_user_id = ? OR actor_user_id = ? ORDER BY created_at DESC
				""", (rs, i) -> new UsageEvent(rs.getObject("period", LocalDate.class), rs.getString("property_ref"),
				rs.getString("endpoint"), rs.getObject("account_user_id", UUID.class), rs.getObject("actor_user_id", UUID.class),
				instant(rs, "created_at")), userId, userId);
	}

	@Override
	public void deleteUser(UUID userId) {
		tx.executeWithoutResult(status -> {
			// Enquiries keep the user as SET NULL; their contact details are personal data too.
			jdbc.update("DELETE FROM business_enquiries WHERE user_id = ?", userId);
			jdbc.update("DELETE FROM users WHERE id = ?", userId);
		});
	}
}
