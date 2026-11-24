package com.propertyapp.subscriptions;

import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import com.propertyapp.users.AccountStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Plans, the caller's subscription and usage, checkout, the billing portal and payment webhooks. */
@RestController
@RequestMapping("/api/v1")
class BillingController {

	private final BillingService billing;

	BillingController(BillingService billing) {
		this.billing = billing;
	}

	record PlanView(
			String id, String name, Integer monthlyPriceCents, boolean customPricing, Integer analysesPerMonth,
			int maxInspectionPhotos, int maxCompare, List<String> features, List<String> highlights, boolean selfServe) {}

	record Plans(List<PlanView> plans, String note, String billingMode) {}

	/** Public. */
	@GetMapping("/plans")
	Plans plans() {
		return new Plans(PlanCatalog.PLANS.stream().map(p -> new PlanView(p.id(), p.name(), p.monthlyPriceCents(),
						p.monthlyPriceCents() == null, p.analysesPerMonth(), p.maxInspectionPhotos(), p.maxCompare(),
						p.features().stream().map(Feature::id).toList(), p.highlights(), p.selfServe())).toList(),
				"Prices are initial hypotheses being tested, not final prices. One analysis = one property analysed in a calendar month.",
				billing.mode());
	}

	@GetMapping("/subscription")
	BillingService.View subscription(Caller caller) {
		return billing.view(caller);
	}

	record CheckoutRequest(@NotBlank String plan) {}

	@PostMapping("/billing/checkout")
	BillingService.Redirect checkout(Caller caller, @Valid @RequestBody CheckoutRequest request) {
		return billing.checkout(caller, request.plan());
	}

	@PostMapping("/billing/portal")
	BillingService.Redirect portal(Caller caller) {
		return billing.portal(caller);
	}

	@PostMapping("/billing/cancel")
	BillingService.View cancel(Caller caller) {
		billing.cancelDemo(caller);
		return billing.refreshedView(caller);
	}

	/** Public: Stripe calls it; authenticity is checked with the Stripe-Signature header. */
	@PostMapping("/billing/webhook")
	Map<String, Object> webhook(@RequestBody String payload, @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
		return Map.of("received", true, "duplicate", !billing.handleWebhook(payload, signature));
	}

	record EnquiryRequest(
			@NotBlank @Size(max = 120) String company,
			@NotBlank @Email @Size(max = 200) String contactEmail,
			@Min(1) @Max(500) Integer seats,
			@Min(1) @Max(1_000_000) Integer monthlyAnalyses,
			@Size(max = 2000) String message) {}

	@PostMapping("/billing/business-enquiry")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, String> enquiry(Caller caller, @Valid @RequestBody EnquiryRequest r) {
		billing.enquiry(caller, new AccountStore.Enquiry(null, r.company().trim(), r.contactEmail().trim(), r.seats(),
				r.monthlyAnalyses(), r.message()));
		return Map.of("status", "received", "message", "Thank you. We will contact you about a Business plan.");
	}
}
