package com.propertyapp.privacy;

import com.propertyapp.audit.Audit;
import com.propertyapp.audit.AuditLog;
import com.propertyapp.inspection.PhotoStorage;
import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.users.AccountService;
import com.propertyapp.users.AccountService.AccountException;
import com.propertyapp.users.AccountStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data-subject rights (GDPR art. 15, 17 and 20): a machine-readable export of everything the
 * platform keeps about the user, and erasure of the account with its data.
 */
public class PrivacyService {

	public static final String EXPORT_FORMAT = "property-intelligence-export/1";

	private final AccountStore accounts;
	private final AccountService accountService;
	private final PrivacyStore store;
	private final ConsentService consents;
	private final AuditLog auditLog;
	private final Audit audit;
	private final PhotoStorage storage;
	private final Clock clock;

	public PrivacyService(AccountStore accounts, AccountService accountService, PrivacyStore store, ConsentService consents,
			AuditLog auditLog, Audit audit, PhotoStorage storage, Clock clock) {
		this.accounts = accounts;
		this.accountService = accountService;
		this.store = store;
		this.consents = consents;
		this.auditLog = auditLog;
		this.audit = audit;
		this.storage = storage;
		this.clock = clock;
	}

	/** Everything kept about the user; secrets (password hash, token and key hashes) are never included. */
	public Map<String, Object> export(Caller caller) {
		UUID id = caller.user().id();
		var u = caller.user();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("format", EXPORT_FORMAT);
		out.put("exportedAt", clock.instant());
		out.put("account", Map.of("id", id, "email", u.email(), "fullName", u.fullName(), "role", u.role(),
				"createdAt", String.valueOf(u.createdAt())));
		out.put("subscription", accounts.currentSubscription(id).map(s -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("plan", s.planId());
			m.put("status", s.status());
			m.put("paymentStatus", s.paymentStatus());
			m.put("provider", s.provider());
			m.put("currentPeriodEnd", s.currentPeriodEnd());
			m.put("cancelAtPeriodEnd", s.cancelAtPeriodEnd());
			return (Object) m;
		}).orElse(Map.of("plan", "free")));
		out.put("apiKeys", accounts.apiKeys(id).stream().map(k -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", k.name());
			m.put("prefix", k.prefix());
			m.put("createdAt", k.createdAt());
			m.put("lastUsedAt", k.lastUsedAt());
			m.put("revokedAt", k.revokedAt());
			return m;
		}).toList());
		out.put("team", accounts.organisationOf(id).map(o -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", o.name());
			boolean owner = o.ownerId().equals(id);
			m.put("yourRole", owner ? "owner" : "member");
			// Other people's details are theirs: members see only the team name.
			if (owner) m.put("members", accounts.members(o.id()).stream().filter(x -> !x.userId().equals(id))
					.map(x -> Map.of("email", x.email(), "fullName", x.fullName(), "role", x.role())).toList());
			return (Object) m;
		}).orElse(null));
		out.put("usage", accounts.usageEvents(id).stream().map(e -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("period", e.period().toString());
			m.put("propertyRef", e.propertyRef());
			m.put("endpoint", e.endpoint());
			m.put("countedTo", e.accountId().equals(id) ? "your account" : "your team owner's account");
			m.put("at", e.at());
			return m;
		}).toList());
		out.put("consents", store.consentHistory(id));
		out.put("uploadedFiles", store.filesOf(id).stream().map(f -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("storageKey", f.key());
			m.put("kind", f.kind());
			m.put("propertyRef", f.propertyRef());
			m.put("contentType", f.contentType());
			m.put("sizeBytes", f.sizeBytes());
			m.put("uploadedAt", f.createdAt());
			m.put("deleteAfter", f.deleteAfter());
			return m;
		}).toList());
		out.put("activity", auditLog.forUser(id, 1000).stream().map(PrivacyService::activity).toList());
		out.put("notes", List.of(
				"Uploaded photos and floor plans are listed here; download each one with GET /api/v1/files?key=<storageKey>.",
				"Saved properties are kept in your browser in this version, not on the server; they are not part of this export.",
				"Payment card details are held by Stripe and never reach this platform; invoices are available in the billing portal."));
		audit.success(caller, "privacy.export", null, Map.of());
		return out;
	}

	public static Map<String, Object> activity(AuditLog.Event e) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("at", e.at());
		m.put("action", e.action());
		m.put("outcome", e.outcome());
		m.put("via", e.actorKind());
		return m;
	}

	public record Erasure(int filesDeleted, String note) {}

	/** Deletes the account and its data after the password is confirmed. */
	public Erasure erase(Caller caller, String password) {
		if (caller.viaApiKey()) throw new AccountException(403, "Delete the account while logged in, not with an API key.");
		UUID id = caller.user().id();
		if (!accountService.passwordMatches(caller.user(), password)) {
			audit.denied(caller, "privacy.erasure", null, Map.of("reason", "password"));
			throw new AccountException(401, "The password is incorrect.");
		}
		var own = accounts.currentSubscription(id);
		if (own.isPresent() && "stripe".equals(own.get().provider()) && !own.get().cancelAtPeriodEnd()) {
			throw new AccountException(409, "Cancel your paid plan in the billing portal first, so you are not charged again; "
					+ "then delete the account.");
		}
		List<String> keys = new ArrayList<>(store.filesOf(id).stream().map(PrivacyStore.StoredFile::key).toList());
		for (String key : keys) storage.delete(key);
		accounts.deleteUser(id);
		store.forget(id);
		auditLog.forget(id);
		// The event has no actor: the account it would point to no longer exists.
		audit.anonymous("privacy.erasure", "success", Audit.hashed("user", id.toString()), Map.of("filesDeleted", keys.size()));
		return new Erasure(keys.size(), "Your account and its data were deleted. Audit events remain without your identity "
				+ "for up to the audit retention period; Stripe keeps invoices as required by tax law.");
	}
}
