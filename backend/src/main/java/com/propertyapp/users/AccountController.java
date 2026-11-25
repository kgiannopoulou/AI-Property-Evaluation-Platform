package com.propertyapp.users;

import com.propertyapp.audit.Audit;
import com.propertyapp.common.RateLimiter;
import com.propertyapp.privacy.ConsentService;
import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.PlanCatalog;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import com.propertyapp.users.AccountStore.ApiKey;
import com.propertyapp.users.AccountStore.Member;
import com.propertyapp.users.AccountStore.Organisation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Registration, login, the current user, API keys (Business) and teams (Business). */
@RestController
@RequestMapping("/api/v1")
class AccountController {

	private final AccountService accounts;
	private final AccountStore store;
	private final Clock clock;
	private final ConsentService consents;
	private final Audit audit;
	/** Per client address, on top of the per-email lockout in AccountService. */
	private final RateLimiter registrations;
	private final RateLimiter logins;

	AccountController(AccountService accounts, AccountStore store, Clock clock, ConsentService consents, Audit audit) {
		this.accounts = accounts;
		this.store = store;
		this.clock = clock;
		this.consents = consents;
		this.audit = audit;
		this.registrations = new RateLimiter(10, Duration.ofHours(1), clock);
		this.logins = new RateLimiter(30, Duration.ofMinutes(10), clock);
	}

	record UserView(UUID id, String email, String fullName, String role) {
		static UserView of(AccountStore.User u) {
			return new UserView(u.id(), u.email(), u.fullName(), u.role());
		}
	}

	record SessionView(String token, Instant expiresAt, UserView user) {}

	/**
	 * @param acceptTerms must be true: the terms (including that results are automated estimates)
	 *     and the privacy notice of {@link ConsentService#POLICY_VERSION}
	 * @param marketingEmails optional, false unless ticked
	 * @param aiPhotoProcessing optional: may also be given later, before the first photo upload
	 */
	record RegisterRequest(String email, String password, String fullName, String role, Boolean acceptTerms,
			Boolean marketingEmails, Boolean aiPhotoProcessing) {}

	record LoginRequest(String email, String password) {}

	@PostMapping("/auth/register")
	@ResponseStatus(HttpStatus.CREATED)
	SessionView register(@RequestBody RegisterRequest r, HttpServletRequest http) {
		if (!registrations.tryAcquire(http.getRemoteAddr())) {
			audit.anonymous("auth.register", "denied", null, Map.of("reason", "rate-limit"));
			throw new AccountService.AccountException(429, "Too many sign-ups from this network. Try again later.");
		}
		if (!Boolean.TRUE.equals(r.acceptTerms())) {
			throw new AccountService.AccountException(400, "Accept the terms and privacy notice to create an account.");
		}
		var s = accounts.register(r.email(), r.password(), r.fullName(), r.role());
		consents.atRegistration(s.user().id(), Boolean.TRUE.equals(r.marketingEmails()), Boolean.TRUE.equals(r.aiPhotoProcessing()));
		audit.user(s.user().id(), "auth.register", "success", null, Map.of("policyVersion", ConsentService.POLICY_VERSION));
		return new SessionView(s.token(), s.expiresAt(), UserView.of(s.user()));
	}

	@PostMapping("/auth/login")
	SessionView login(@RequestBody LoginRequest r, HttpServletRequest http) {
		String target = Audit.hashed("email", r.email());
		if (!logins.tryAcquire(http.getRemoteAddr())) {
			audit.anonymous("auth.login", "denied", target, Map.of("reason", "rate-limit"));
			throw new AccountService.AccountException(429, "Too many sign-in attempts from this network. Try again in a few minutes.");
		}
		try {
			var s = accounts.login(r.email(), r.password());
			audit.user(s.user().id(), "auth.login", "success", null, Map.of());
			return new SessionView(s.token(), s.expiresAt(), UserView.of(s.user()));
		} catch (AccountService.AccountException e) {
			audit.anonymous("auth.login", e.status() == 429 ? "denied" : "failure", target,
					Map.of("reason", e.status() == 429 ? "lockout" : "bad-credentials"));
			throw e;
		}
	}

	@PostMapping("/auth/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(Caller caller, @RequestHeader("Authorization") String authorization) {
		accounts.logout(authorization.substring("Bearer ".length()).trim());
		audit.success(caller, "auth.logout", null, Map.of());
	}

	record Me(UserView user, String plan, boolean teamMember) {}

	@GetMapping("/auth/me")
	Me me(Caller caller) {
		return new Me(UserView.of(caller.user()), caller.plan().id(), !caller.accountId().equals(caller.user().id()));
	}

	// API keys (Business) ------------------------------------------------------------------------

	record NewKeyRequest(@NotBlank @Size(max = 80) String name) {}

	record NewKeyView(ApiKey key, String secret, String note) {}

	@PostMapping("/api-keys")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresFeature(Feature.API_ACCESS)
	NewKeyView createKey(Caller caller, @Valid @RequestBody NewKeyRequest r) {
		if (caller.viaApiKey()) throw new AccountService.AccountException(403, "Create API keys while logged in, not with another key.");
		var k = accounts.createApiKey(caller.user().id(), r.name());
		audit.success(caller, "apikey.create", k.key().prefix(), Map.of());
		return new NewKeyView(k.key(), k.secret(), "Copy the key now: it is shown only once. Send it in the X-API-Key header.");
	}

	@GetMapping("/api-keys")
	@RequiresFeature(Feature.API_ACCESS)
	List<ApiKey> keys(Caller caller) {
		return store.apiKeys(caller.user().id());
	}

	@DeleteMapping("/api-keys/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresFeature(Feature.API_ACCESS)
	void revoke(Caller caller, @PathVariable UUID id) {
		if (!store.revokeApiKey(caller.user().id(), id, clock.instant())) throw new AccountService.AccountException(404, "No such active key.");
		audit.success(caller, "apikey.revoke", id.toString(), Map.of());
	}

	// Teams (Business) ---------------------------------------------------------------------------

	record TeamView(UUID id, String name, int seats, List<Member> members) {}

	record CreateTeamRequest(@NotBlank @Size(max = 120) String name) {}

	record AddMemberRequest(@NotBlank String email, @Pattern(regexp = "admin|member") String role) {}

	@GetMapping("/team")
	TeamView team(Caller caller) {
		Organisation o = store.organisationOf(caller.user().id())
				.orElseThrow(() -> new AccountService.AccountException(404, "You are not in a team."));
		return view(o, caller);
	}

	@PostMapping("/team")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresFeature(Feature.TEAMS)
	TeamView createTeam(Caller caller, @Valid @RequestBody CreateTeamRequest r) {
		try {
			var team = store.createOrganisation(caller.user().id(), r.name().trim());
			audit.success(caller, "team.create", team.id().toString(), Map.of());
			return view(team, caller);
		} catch (AccountStore.AlreadyInTeamException e) {
			throw new AccountService.AccountException(409, "You already own or belong to a team.");
		}
	}

	@PostMapping("/team/members")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresFeature(Feature.TEAMS)
	TeamView addMember(Caller caller, @Valid @RequestBody AddMemberRequest r) {
		Organisation o = ownedTeam(caller);
		int seats = seats(caller);
		if (store.members(o.id()).size() >= seats) {
			throw new AccountService.AccountException(409, "All " + seats + " seats are used. Contact us to add seats.");
		}
		var user = store.userByEmail(r.email().trim())
				.orElseThrow(() -> new AccountService.AccountException(404, "No account with that email. Ask them to register first."));
		try {
			store.addMember(o.id(), user.id(), r.role() == null ? "member" : r.role());
			audit.success(caller, "team.member-add", user.id().toString(), Map.of("role", r.role() == null ? "member" : r.role()));
		} catch (AccountStore.AlreadyInTeamException e) {
			throw new AccountService.AccountException(409, "That person is already in a team.");
		}
		return view(o, caller);
	}

	@DeleteMapping("/team/members/{userId}")
	@RequiresFeature(Feature.TEAMS)
	TeamView removeMember(Caller caller, @PathVariable UUID userId) {
		Organisation o = ownedTeam(caller);
		if (!store.removeMember(o.id(), userId)) throw new AccountService.AccountException(404, "Not a member of your team.");
		audit.success(caller, "team.member-remove", userId.toString(), Map.of());
		return view(o, caller);
	}

	private Organisation ownedTeam(Caller caller) {
		return store.organisationOf(caller.user().id()).filter(o -> o.ownerId().equals(caller.user().id()))
				.orElseThrow(() -> new AccountService.AccountException(404, "Create a team first; only the owner manages members."));
	}

	private int seats(Caller caller) {
		return caller.subscription() != null && caller.subscription().seats() != null ? caller.subscription().seats()
				: PlanCatalog.BUSINESS_DEFAULT_SEATS;
	}

	private TeamView view(Organisation o, Caller caller) {
		return new TeamView(o.id(), o.name(), seats(caller), store.members(o.id()));
	}
}
