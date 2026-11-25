package com.propertyapp.privacy;

import com.propertyapp.privacy.PrivacyStore.Consent;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * GDPR consent: what each user agreed to, under which policy version, and when.
 *
 * <ul>
 *   <li>{@code terms} and {@code privacy-notice}: needed for an account. Accepted at registration;
 *       they cannot be withdrawn while the account exists (deleting the account ends them).</li>
 *   <li>{@code ai-photo-processing}: sending photos and floor plans to the AI provider. Asked before
 *       the first upload, enforced by the backend, and can be withdrawn at any time.</li>
 *   <li>{@code marketing-email}: optional, off unless the user opts in.</li>
 * </ul>
 */
public class ConsentService {

	/** Version of the terms and privacy notice; a new version is shown and accepted again. */
	public static final String POLICY_VERSION = "2026-11-25";

	public enum Purpose {
		TERMS("terms", true),
		PRIVACY_NOTICE("privacy-notice", true),
		AI_PHOTO_PROCESSING("ai-photo-processing", false),
		MARKETING_EMAIL("marketing-email", false);

		private final String id;
		private final boolean requiredForAccount;

		Purpose(String id, boolean requiredForAccount) {
			this.id = id;
			this.requiredForAccount = requiredForAccount;
		}

		public String id() {
			return id;
		}

		public boolean requiredForAccount() {
			return requiredForAccount;
		}

		public static Optional<Purpose> of(String id) {
			return Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst();
		}
	}

	/** 403: the action needs a consent the user has not given (or has withdrawn). */
	public static class ConsentRequired extends RuntimeException {
		private final Purpose purpose;

		public ConsentRequired(Purpose purpose, String message) {
			super(message);
			this.purpose = purpose;
		}

		public Purpose purpose() {
			return purpose;
		}
	}

	public static class ConsentException extends RuntimeException {
		private final int status;

		public ConsentException(int status, String message) {
			super(message);
			this.status = status;
		}

		public int status() {
			return status;
		}
	}

	private final PrivacyStore store;
	private final Clock clock;

	public ConsentService(PrivacyStore store, Clock clock) {
		this.store = store;
		this.clock = clock;
	}

	/** The latest decision per purpose; purposes never decided are absent. */
	public Map<Purpose, Consent> current(UUID userId) {
		Map<Purpose, Consent> out = new LinkedHashMap<>();
		for (Consent c : store.consentHistory(userId)) {
			Purpose.of(c.purpose()).ifPresent(p -> out.putIfAbsent(p, c));
		}
		return out;
	}

	public boolean granted(UUID userId, Purpose purpose) {
		Consent c = current(userId).get(purpose);
		return c != null && c.granted();
	}

	public void require(UUID userId, Purpose purpose) {
		if (granted(userId, purpose)) return;
		throw new ConsentRequired(purpose, switch (purpose) {
			case AI_PHOTO_PROCESSING -> "Photos and floor plans are analysed by an AI provider. Allow AI photo processing to continue.";
			default -> "This needs your consent (" + purpose.id() + ").";
		});
	}

	/** Records the decisions given with a new account. */
	public void atRegistration(UUID userId, boolean marketingEmail, boolean aiPhotoProcessing) {
		record(userId, Purpose.TERMS, true, "registration");
		record(userId, Purpose.PRIVACY_NOTICE, true, "registration");
		record(userId, Purpose.MARKETING_EMAIL, marketingEmail, "registration");
		if (aiPhotoProcessing) record(userId, Purpose.AI_PHOTO_PROCESSING, true, "registration");
	}

	/** A decision made later (settings page or the prompt before an upload). */
	public Consent decide(UUID userId, Purpose purpose, boolean granted, String source) {
		if (purpose.requiredForAccount() && !granted) {
			throw new ConsentException(409, "The terms and privacy notice are needed for an account. Delete the account to end them.");
		}
		if (!source.equals("settings") && !source.equals("prompt")) throw new ConsentException(400, "Unknown source.");
		return record(userId, purpose, granted, source);
	}

	private Consent record(UUID userId, Purpose purpose, boolean granted, String source) {
		Consent c = new Consent(purpose.id(), granted, POLICY_VERSION, source, clock.instant());
		store.recordConsent(userId, c);
		return c;
	}
}
