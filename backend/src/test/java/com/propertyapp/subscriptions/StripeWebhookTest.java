package com.propertyapp.subscriptions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.propertyapp.TestAccounts;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Plan changes arrive only through verified Stripe webhooks. */
@SpringBootTest
@TestPropertySource(properties = {
		"app.database.url=",
		"app.billing.demo-mode=true", // ignored: Stripe is configured
		"app.billing.stripe.secret-key=sk_test_123",
		"app.billing.stripe.webhook-secret=whsec_test",
		"app.billing.stripe.price-premium=price_premium",
		"app.billing.stripe.price-professional=price_pro"})
class StripeWebhookTest {

	@Autowired
	WebApplicationContext context;

	@Autowired
	StripePayments stripe;

	MockMvc webhook(String payload, String signature) throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
		mvc.perform(post("/api/v1/billing/webhook").contentType(MediaType.APPLICATION_JSON).content(payload)
				.header("Stripe-Signature", signature));
		return mvc;
	}

	String signed(String payload) {
		return StripePaymentsTest.sign(stripe, payload, Instant.now().getEpochSecond());
	}

	@Test
	void subscriptionLifecycle() throws Exception {
		var account = TestAccounts.create(context, "free");
		MockMvc user = TestAccounts.mvc(context, account);
		String uid = account.session().user().id().toString();
		MockMvc anon = MockMvcBuilders.webAppContextSetup(context).build();

		user.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.billingMode").value("stripe"));

		String completed = """
				{"id":"evt_1","type":"checkout.session.completed","data":{"object":{"client_reference_id":"%s",
				 "subscription":"sub_1","customer":"cus_1","payment_status":"paid","metadata":{"plan":"premium"}}}}""".formatted(uid);
		anon.perform(post("/api/v1/billing/webhook").content(completed).header("Stripe-Signature", "t=1,v1=bad"))
				.andExpect(status().isBadRequest());
		anon.perform(post("/api/v1/billing/webhook").content(completed).header("Stripe-Signature", signed(completed)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(false));
		anon.perform(post("/api/v1/billing/webhook").content(completed).header("Stripe-Signature", signed(completed)))
				.andExpect(jsonPath("$.duplicate").value(true));
		user.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.plan").value("premium"))
				.andExpect(jsonPath("$.paymentStatus").value("paid"));

		long end = Instant.now().getEpochSecond() + 30 * 86400;
		String upgraded = """
				{"id":"evt_2","type":"customer.subscription.updated","data":{"object":{"id":"sub_1","customer":"cus_1",
				 "status":"active","cancel_at_period_end":true,"items":{"data":[{"price":{"id":"price_pro"},
				 "current_period_start":%d,"current_period_end":%d}]}}}}""".formatted(end - 30 * 86400, end);
		anon.perform(post("/api/v1/billing/webhook").content(upgraded).header("Stripe-Signature", signed(upgraded))).andExpect(status().isOk());
		user.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.plan").value("professional"))
				.andExpect(jsonPath("$.cancelAtPeriodEnd").value(true))
				.andExpect(jsonPath("$.currentPeriodEnd").isString());

		String failed = """
				{"id":"evt_3","type":"invoice.payment_failed","data":{"object":{"subscription":"sub_1"}}}""";
		anon.perform(post("/api/v1/billing/webhook").content(failed).header("Stripe-Signature", signed(failed))).andExpect(status().isOk());
		user.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.status").value("past_due"))
				.andExpect(jsonPath("$.plan").value("professional")); // kept while Stripe retries the card

		String deleted = """
				{"id":"evt_4","type":"customer.subscription.deleted","data":{"object":{"id":"sub_1","status":"canceled"}}}""";
		anon.perform(post("/api/v1/billing/webhook").content(deleted).header("Stripe-Signature", signed(deleted))).andExpect(status().isOk());
		user.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.plan").value("free"));

		// With Stripe configured the demo switch is off, and a second checkout is refused while subscribed.
		user.perform(post("/api/v1/billing/cancel")).andExpect(status().isConflict());
	}
}
