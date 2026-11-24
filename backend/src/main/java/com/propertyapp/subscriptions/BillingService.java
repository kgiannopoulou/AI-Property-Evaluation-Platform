package com.propertyapp.subscriptions;

import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.Access.Entitlements;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import com.propertyapp.subscriptions.PlanCatalog.Plan;
import com.propertyapp.users.AccountStore;
import com.propertyapp.users.AccountStore.Subscription;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subscriptions and payments. With Stripe configured, plans are bought through Stripe Checkout and
 * every change (renewal, failed payment, plan switch, cancellation) arrives through a verified
 * webhook. Without Stripe, an explicit demo mode can switch plans locally for development; it is off
 * unless {@code BILLING_DEMO_MODE=true} and never active when Stripe is configured.
 */
public class BillingService {

	private static final Logger log = LoggerFactory.getLogger(BillingService.class);

	public record Settings(boolean demoMode, String frontendUrl) {}

	public static class BillingException extends RuntimeException {
		private final int status;

		public BillingException(int status, String message) {
			super(message);
			this.status = status;
		}

		public int status() {
			return status;
		}
	}

	private final AccountStore store;
	private final Entitlements entitlements;
	private final StripePayments stripe;
	private final Settings settings;
	private final Clock clock;

	public BillingService(AccountStore store, Entitlements entitlements, StripePayments stripe, Settings settings, Clock clock) {
		this.store = store;
		this.entitlements = entitlements;
		this.stripe = stripe;
		this.settings = settings;
		this.clock = clock;
	}

	/** stripe, demo or disabled. */
	public String mode() {
		return stripe.settings().configured() ? "stripe" : settings.demoMode() ? "demo" : "disabled";
	}

	// Views --------------------------------------------------------------------------------------

	public record Usage(int used, int limit, int remaining, LocalDate resetsOn) {}

	public record View(
			String plan, String planName, String status, String paymentStatus, Instant currentPeriodEnd, boolean cancelAtPeriodEnd,
			boolean teamMember, boolean canManageBilling, Usage usage, List<String> features, int maxInspectionPhotos,
			int maxCompare, String billingMode) {}

	public View view(Caller c) {
		int used = entitlements.used(c);
		Subscription s = c.subscription();
		boolean member = !c.accountId().equals(c.user().id());
		return new View(c.plan().id(), c.plan().name(), s == null ? "free" : s.status(), s == null ? "none" : s.paymentStatus(),
				s == null ? null : s.currentPeriodEnd(), s != null && s.cancelAtPeriodEnd(), member, !member,
				new Usage(used, c.analysesLimit(), Math.max(0, c.analysesLimit() - used), entitlements.resetsOn()),
				c.plan().features().stream().map(Feature::id).toList(), c.plan().maxInspectionPhotos(), c.plan().maxCompare(), mode());
	}

	/** The view after a change made in this request (the caller's plan was resolved before it). */
	public View refreshedView(Caller c) {
		return view(entitlements.resolve(c.user(), c.viaApiKey()));
	}

	// Checkout and portal --------------------------------------------------------------------------

	public record Redirect(String url, String mode) {}

	public Redirect checkout(Caller c, String planId) {
		ownerOnly(c);
		Plan plan = PlanCatalog.find(planId).orElseThrow(() -> new BillingException(400, "Unknown plan."));
		if (plan.id().equals("free")) throw new BillingException(400, "The Free plan needs no checkout; cancel the paid plan instead.");
		String success = settings.frontendUrl() + "/settings?billing=success";
		String cancel = settings.frontendUrl() + "/pricing?billing=cancelled";

		switch (mode()) {
			case "stripe" -> {
				if (!plan.selfServe()) throw new BillingException(400, "Business plans are arranged by contract: send an enquiry.");
				Subscription current = c.subscription();
				if (current != null && "stripe".equals(current.provider())) {
					throw new BillingException(409, "You already have a subscription: change plan in the billing portal.");
				}
				String customer = current == null ? null : current.providerCustomer();
				return new Redirect(stripe.checkout(c.user().id().toString(), c.user().email(), customer, plan.id(), success, cancel), "stripe");
			}
			case "demo" -> {
				activateDemo(c.user().id(), plan);
				return new Redirect(success, "demo");
			}
			default -> throw new BillingException(503, "Payments are not configured on this server.");
		}
	}

	public Redirect portal(Caller c) {
		ownerOnly(c);
		Subscription s = c.subscription();
		if (!mode().equals("stripe") || s == null || s.providerCustomer() == null) {
			throw new BillingException(409, "There is no Stripe subscription to manage.");
		}
		return new Redirect(stripe.portal(s.providerCustomer(), settings.frontendUrl() + "/settings"), "stripe");
	}

	/** Demo mode only; Stripe subscriptions are cancelled in the billing portal. */
	public void cancelDemo(Caller c) {
		ownerOnly(c);
		Subscription s = c.subscription();
		if (s == null || !"demo".equals(s.provider())) throw new BillingException(409, "There is no demo subscription to cancel.");
		store.saveSubscription(with(s, s.planId(), "canceled", s.paymentStatus(), s.currentPeriodEnd(), false));
	}

	private void activateDemo(UUID userId, Plan plan) {
		store.currentSubscription(userId).ifPresent(old -> store.saveSubscription(with(old, old.planId(), "canceled", old.paymentStatus(), old.currentPeriodEnd(), false)));
		Instant now = clock.instant();
		store.saveSubscription(new Subscription(null, userId, plan.id(), "active", "none", "demo", null, null, now,
				now.plus(java.time.Duration.ofDays(30)), false, null, plan.id().equals("business") ? PlanCatalog.BUSINESS_DEFAULT_SEATS : null));
	}

	private static void ownerOnly(Caller c) {
		if (!c.accountId().equals(c.user().id())) throw new BillingException(403, "Only the team owner manages billing.");
		if (c.viaApiKey()) throw new BillingException(403, "Billing is not available with an API key.");
	}

	// Webhooks -----------------------------------------------------------------------------------

	/** Applies a verified Stripe event once. Returns false when the event was a duplicate. */
	public boolean handleWebhook(String payload, String signature) {
		if (!stripe.verify(payload, signature)) throw new BillingException(400, "Invalid webhook signature.");
		JsonNode event = JsonMapper.shared().readTree(payload);
		String id = event.path("id").asString("");
		String type = event.path("type").asString("");
		if (id.isBlank()) throw new BillingException(400, "Event without an id.");
		if (!store.recordBillingEvent(id, type)) return false;
		JsonNode o = event.path("data").path("object");
		switch (type) {
			case "checkout.session.completed" -> checkoutCompleted(o);
			case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" -> subscriptionChanged(o, type);
			case "invoice.paid", "invoice.payment_succeeded" -> invoice(o, true);
			case "invoice.payment_failed" -> invoice(o, false);
			default -> log.debug("Ignored Stripe event {}", type);
		}
		return true;
	}

	private void checkoutCompleted(JsonNode o) {
		UUID userId = uuid(o.path("client_reference_id").asString(""));
		String subId = o.path("subscription").asString("");
		String planId = o.path("metadata").path("plan").asString("");
		if (userId == null || subId.isBlank() || PlanCatalog.find(planId).isEmpty() || store.userById(userId).isEmpty()) {
			log.warn("checkout.session.completed without a known user, subscription or plan");
			return;
		}
		Optional<Subscription> existing = store.subscriptionByProviderId("stripe", subId);
		if (existing.isPresent()) return; // customer.subscription.created arrived first
		replaceActive(userId, new Subscription(null, userId, planId, "active",
				"paid".equals(o.path("payment_status").asString("")) ? "paid" : "pending", "stripe", subId,
				o.path("customer").asString(null), clock.instant(), null, false, null, null));
	}

	private void subscriptionChanged(JsonNode o, String type) {
		String subId = o.path("id").asString("");
		String status = type.endsWith("deleted") ? "canceled" : mapStatus(o.path("status").asString(""));
		JsonNode item = o.path("items").path("data").path(0);
		String planId = stripe.planForPrice(item.path("price").path("id").asString("")).orElse(null);
		Instant start = epoch(o.has("current_period_start") ? o.path("current_period_start") : item.path("current_period_start"));
		Instant end = epoch(o.has("current_period_end") ? o.path("current_period_end") : item.path("current_period_end"));
		boolean cancelAtEnd = o.path("cancel_at_period_end").asBoolean(false);

		Optional<Subscription> existing = store.subscriptionByProviderId("stripe", subId);
		if (existing.isPresent()) {
			Subscription s = existing.get();
			if (status == null) return;
			Subscription updated = new Subscription(s.id(), s.userId(), planId != null ? planId : s.planId(), status, s.paymentStatus(),
					s.provider(), s.providerSubscription(), o.path("customer").asString(s.providerCustomer()), start != null ? start : s.currentPeriodStart(),
					end != null ? end : s.currentPeriodEnd(), cancelAtEnd, s.analysesLimit(), s.seats());
			if (updated.grantsPlan() && !s.grantsPlan()) replaceActive(s.userId(), updated);
			else store.saveSubscription(updated);
			return;
		}
		UUID userId = uuid(o.path("metadata").path("user_id").asString(""));
		if (userId == null || status == null || planId == null || store.userById(userId).isEmpty()) {
			log.warn("Stripe subscription {} without a known user or plan", subId);
			return;
		}
		replaceActive(userId, new Subscription(null, userId, planId, status, "pending", "stripe", subId,
				o.path("customer").asString(null), start, end, cancelAtEnd, null, null));
	}

	private void invoice(JsonNode o, boolean paid) {
		String subId = o.path("subscription").asString("");
		if (subId.isBlank()) subId = o.path("parent").path("subscription_details").path("subscription").asString("");
		if (subId.isBlank()) return;
		store.subscriptionByProviderId("stripe", subId).ifPresent(s -> store.saveSubscription(with(s, s.planId(),
				paid ? (s.status().equals("past_due") ? "active" : s.status()) : (s.status().equals("canceled") ? "canceled" : "past_due"),
				paid ? "paid" : "failed", s.currentPeriodEnd(), s.cancelAtPeriodEnd())));
	}

	/** One active subscription per user: an earlier one (e.g. demo) is closed first. */
	private void replaceActive(UUID userId, Subscription next) {
		store.currentSubscription(userId)
				.filter(old -> next.id() == null || !old.id().equals(next.id()))
				.ifPresent(old -> store.saveSubscription(with(old, old.planId(), "canceled", old.paymentStatus(), old.currentPeriodEnd(), false)));
		store.saveSubscription(next);
	}

	static String mapStatus(String stripeStatus) {
		return switch (stripeStatus) {
			case "active" -> "active";
			case "trialing" -> "trialing";
			case "past_due", "unpaid" -> "past_due";
			case "canceled", "incomplete_expired" -> "canceled";
			default -> null; // incomplete, paused: no change until Stripe settles it
		};
	}

	private static Subscription with(Subscription s, String planId, String status, String paymentStatus, Instant periodEnd, boolean cancelAtEnd) {
		return new Subscription(s.id(), s.userId(), planId, status, paymentStatus, s.provider(), s.providerSubscription(),
				s.providerCustomer(), s.currentPeriodStart(), periodEnd, cancelAtEnd, s.analysesLimit(), s.seats());
	}

	private static Instant epoch(JsonNode n) {
		return n == null || n.isMissingNode() || n.isNull() ? null : Instant.ofEpochSecond(n.asLong());
	}

	private static UUID uuid(String s) {
		try {
			return s == null || s.isBlank() ? null : UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	// Business enquiries --------------------------------------------------------------------------

	public void enquiry(Caller c, AccountStore.Enquiry e) {
		store.saveEnquiry(new AccountStore.Enquiry(c.user().id(), e.company(), e.contactEmail(), e.seats(), e.monthlyAnalyses(), e.message()));
	}
}
