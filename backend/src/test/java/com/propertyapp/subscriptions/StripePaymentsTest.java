package com.propertyapp.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

class StripePaymentsTest {

	static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
	static final StripePayments.Settings SETTINGS =
			new StripePayments.Settings("sk_test_123", "whsec_test", Map.of("premium", "price_premium", "professional", "price_pro"));

	public static String sign(StripePayments stripe, String payload, long timestamp) {
		return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(stripe.hmac(timestamp + "." + payload));
	}

	@Test
	void verifiesSignaturesWithinTheTolerance() {
		var stripe = new StripePayments(RestClient.builder(), SETTINGS, Clock.fixed(NOW, ZoneOffset.UTC));
		String payload = "{\"id\":\"evt_1\"}";
		long t = NOW.getEpochSecond();
		assertThat(stripe.verify(payload, sign(stripe, payload, t))).isTrue();
		String good = sign(stripe, payload, t);
		String tampered = good.substring(0, good.length() - 1) + (good.endsWith("0") ? "1" : "0");
		assertThat(stripe.verify(payload, tampered)).isFalse();
		assertThat(stripe.verify(payload + " ", sign(stripe, payload, t))).isFalse();
		assertThat(stripe.verify(payload, sign(stripe, payload, t - 301))).isFalse();
		assertThat(stripe.verify(payload, "v1=abc")).isFalse();
		assertThat(stripe.verify(payload, null)).isFalse();
	}

	@Test
	void createsACheckoutSessionWithTheUserAndPlan() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		var stripe = new StripePayments(builder, SETTINGS, Clock.fixed(NOW, ZoneOffset.UTC));
		var form = new LinkedMultiValueMap<String, String>();
		form.add("mode", "subscription");
		form.add("line_items[0][price]", "price_premium");
		form.add("line_items[0][quantity]", "1");
		form.add("success_url", "http://localhost:3000/settings?billing=success");
		form.add("cancel_url", "http://localhost:3000/pricing?billing=cancelled");
		form.add("client_reference_id", "u-1");
		form.add("customer_email", "a@example.test");
		form.add("metadata[user_id]", "u-1");
		form.add("metadata[plan]", "premium");
		form.add("subscription_data[metadata][user_id]", "u-1");
		form.add("subscription_data[metadata][plan]", "premium");
		server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions"))
				.andExpect(header("Authorization", "Bearer sk_test_123"))
				.andExpect(content().formData(form))
				.andRespond(withSuccess("{\"id\":\"cs_1\",\"url\":\"https://checkout.stripe.com/c/pay/cs_1\"}", MediaType.APPLICATION_JSON));

		String url = stripe.checkout("u-1", "a@example.test", null, "premium", "http://localhost:3000/settings?billing=success",
				"http://localhost:3000/pricing?billing=cancelled");
		assertThat(url).isEqualTo("https://checkout.stripe.com/c/pay/cs_1");
		server.verify();
		assertThat(stripe.planForPrice("price_pro")).contains("professional");
	}
}
