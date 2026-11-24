package com.propertyapp.subscriptions;

import com.propertyapp.subscriptions.PlanCatalog.Feature;
import com.propertyapp.subscriptions.PlanCatalog.Plan;
import com.propertyapp.users.AccountStore;
import com.propertyapp.users.AccountStore.Subscription;
import com.propertyapp.users.AccountStore.User;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Who is calling, what their plan allows, and the errors when it does not. */
public final class Access {

	private Access() {}

	/** The endpoint needs this feature in the caller's plan. */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public @interface RequiresFeature {
		Feature value();
	}

	/** Each successful call analyses one property and counts against the monthly allowance. */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public @interface Metered {}

	/**
	 * @param accountId who pays and whose allowance is used: the team owner for team members
	 * @param analysesLimit monthly allowance of the account's plan or contract
	 */
	public record Caller(User user, UUID accountId, Plan plan, Subscription subscription, int analysesLimit, boolean viaApiKey) {

		public boolean has(Feature f) {
			return plan.has(f);
		}

		public void require(Feature f) {
			if (!has(f)) throw new UpgradeRequired(f, plan);
		}
	}

	/** 401: no valid session token or API key. */
	public static class Unauthenticated extends RuntimeException {
		public Unauthenticated(String message) {
			super(message);
		}
	}

	/** 403: the plan does not include the feature. */
	public static class UpgradeRequired extends RuntimeException {
		private final Feature feature;
		private final Plan current;

		public UpgradeRequired(Feature feature, Plan current) {
			super("The " + current.name() + " plan does not include " + lowerFirst(feature.label()) + ". Upgrade to "
					+ PlanCatalog.cheapestWith(feature).name() + " to use it.");
			this.feature = feature;
			this.current = current;
		}

		public UpgradeRequired(String message, Feature feature, Plan current) {
			super(message);
			this.feature = feature;
			this.current = current;
		}

		/** "Location intelligence" → "location intelligence"; acronyms such as "PDF" and "AI" are kept. */
		static String lowerFirst(String label) {
			return label.length() > 1 && Character.isLowerCase(label.charAt(1))
					? Character.toLowerCase(label.charAt(0)) + label.substring(1) : label;
		}

		public Feature feature() {
			return feature;
		}

		public Plan current() {
			return current;
		}
	}

	/** 429: the monthly allowance is used up. */
	public static class QuotaExceeded extends RuntimeException {
		private final Plan plan;
		private final int limit;
		private final int used;
		private final LocalDate resetsOn;

		public QuotaExceeded(Plan plan, int limit, int used, LocalDate resetsOn) {
			super(String.format("You have used %d of %d analyses this month on the %s plan. The allowance resets on %s%s",
					used, limit, plan.name(), resetsOn, plan.id().equals("business") ? "." : "; upgrade for more."));
			this.plan = plan;
			this.limit = limit;
			this.used = used;
			this.resetsOn = resetsOn;
		}

		public Plan plan() {
			return plan;
		}

		public int limit() {
			return limit;
		}

		public int used() {
			return used;
		}

		public LocalDate resetsOn() {
			return resetsOn;
		}
	}

	/** Resolves a user's plan, allowance and the account that pays (team owner for members). */
	public static class Entitlements {

		private final AccountStore store;
		private final Clock clock;

		public Entitlements(AccountStore store, Clock clock) {
			this.store = store;
			this.clock = clock;
		}

		public Caller resolve(User user, boolean viaApiKey) {
			UUID account = user.id();
			var team = store.organisationOf(user.id());
			if (team.isPresent() && !team.get().ownerId().equals(user.id())) {
				// Members use the owner's plan only while it includes teams.
				var ownerSub = store.currentSubscription(team.get().ownerId());
				Plan ownerPlan = ownerSub.map(s -> PlanCatalog.get(s.planId())).orElse(PlanCatalog.free());
				if (ownerPlan.has(Feature.TEAMS)) account = team.get().ownerId();
			}
			Subscription sub = store.currentSubscription(account).orElse(null);
			Plan plan = sub == null ? PlanCatalog.free() : PlanCatalog.get(sub.planId());
			int limit = plan.analysesPerMonth() != null ? plan.analysesPerMonth()
					: sub != null && sub.analysesLimit() != null ? sub.analysesLimit() : PlanCatalog.BUSINESS_DEFAULT_ANALYSES;
			return new Caller(user, account, plan, sub, limit, viaApiKey);
		}

		public LocalDate period() {
			return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
		}

		public LocalDate resetsOn() {
			return period().plusMonths(1);
		}

		public int used(Caller c) {
			return store.usage(c.accountId(), period());
		}

		/** Counts the properties (all or none) or throws {@link QuotaExceeded}. */
		public AccountStore.Usage consume(Caller c, List<String> propertyRefs, String endpoint) {
			var usage = store.consume(c.accountId(), c.user().id(), period(), propertyRefs, endpoint, c.analysesLimit());
			if (!usage.allowed()) throw new QuotaExceeded(c.plan(), c.analysesLimit(), usage.used(), resetsOn());
			return usage;
		}

		public void release(List<UUID> eventIds) {
			if (!eventIds.isEmpty()) store.releaseUsage(eventIds);
		}
	}
}
