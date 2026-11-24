package com.propertyapp.subscriptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe over its REST API: Checkout for new subscriptions, the Customer Portal for changes and
 * cancellation, and webhook signature verification. Card details never reach this backend.
 */
public class StripePayments {

	/** Webhooks older than this are rejected (replay protection), seconds. */
	static final long TOLERANCE_SECONDS = 300;

	/**
	 * @param prices plan id → Stripe price id (e.g. premium → price_123)
	 */
	public record Settings(String secretKey, String webhookSecret, Map<String, String> prices) {

		public boolean configured() {
			return secretKey != null && !secretKey.isBlank();
		}
	}

	public static class PaymentException extends RuntimeException {
		public PaymentException(String message) {
			super(message);
		}
	}

	private final RestClient http;
	private final Settings settings;
	private final Clock clock;

	public StripePayments(RestClient.Builder builder, Settings settings, Clock clock) {
		this.http = builder.baseUrl("https://api.stripe.com")
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (settings.secretKey() == null ? "" : settings.secretKey()))
				.build();
		this.settings = settings;
		this.clock = clock;
	}

	public Settings settings() {
		return settings;
	}

	public Optional<String> priceFor(String planId) {
		return Optional.ofNullable(settings.prices().get(planId)).filter(p -> !p.isBlank());
	}

	public Optional<String> planForPrice(String priceId) {
		return settings.prices().entrySet().stream().filter(e -> e.getValue().equals(priceId)).map(Map.Entry::getKey).findFirst();
	}

	/** Creates a hosted Checkout page for a subscription and returns its URL. */
	public String checkout(String userId, String email, String customerId, String planId, String successUrl, String cancelUrl) {
		String price = priceFor(planId).orElseThrow(() -> new PaymentException("No Stripe price is configured for " + planId + "."));
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("mode", "subscription");
		form.add("line_items[0][price]", price);
		form.add("line_items[0][quantity]", "1");
		form.add("success_url", successUrl);
		form.add("cancel_url", cancelUrl);
		form.add("client_reference_id", userId);
		if (customerId != null) form.add("customer", customerId);
		else form.add("customer_email", email);
		form.add("metadata[user_id]", userId);
		form.add("metadata[plan]", planId);
		form.add("subscription_data[metadata][user_id]", userId);
		form.add("subscription_data[metadata][plan]", planId);
		return post("/v1/checkout/sessions", form).path("url").asString();
	}

	/** A Customer Portal session where the subscriber changes plan, updates the card or cancels. */
	public String portal(String customerId, String returnUrl) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("customer", customerId);
		form.add("return_url", returnUrl);
		return post("/v1/billing_portal/sessions", form).path("url").asString();
	}

	private JsonNode post(String path, MultiValueMap<String, String> form) {
		try {
			String body = http.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve()
					.onStatus(HttpStatusCode::isError, (req, res) -> {
						String message = JsonMapper.shared().readTree(res.getBody().readAllBytes()).path("error").path("message").asString("");
						throw new PaymentException("The payment provider refused the request" + (message.isBlank() ? "." : ": " + message));
					})
					.body(String.class);
			return JsonMapper.shared().readTree(body);
		} catch (ResourceAccessException e) {
			throw new PaymentException("The payment provider is not reachable.");
		}
	}

	/**
	 * Verifies a {@code Stripe-Signature} header ({@code t=…,v1=…}) against the raw request body:
	 * HMAC-SHA256 of {@code t + "." + body} with the endpoint secret, within the time tolerance.
	 */
	public boolean verify(String payload, String header) {
		if (settings.webhookSecret() == null || settings.webhookSecret().isBlank() || header == null) return false;
		long timestamp = -1;
		java.util.List<String> signatures = new java.util.ArrayList<>();
		for (String part : header.split(",")) {
			String[] kv = part.trim().split("=", 2);
			if (kv.length != 2) continue;
			if (kv[0].equals("t")) {
				try {
					timestamp = Long.parseLong(kv[1]);
				} catch (NumberFormatException e) {
					return false;
				}
			} else if (kv[0].equals("v1")) {
				signatures.add(kv[1]);
			}
		}
		if (timestamp < 0 || signatures.isEmpty()) return false;
		if (Math.abs(clock.instant().getEpochSecond() - timestamp) > TOLERANCE_SECONDS) return false;
		byte[] expected = HexFormat.of().formatHex(hmac(timestamp + "." + payload)).getBytes(StandardCharsets.US_ASCII);
		return signatures.stream().anyMatch(s -> MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.US_ASCII)));
	}

	byte[] hmac(String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(settings.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
