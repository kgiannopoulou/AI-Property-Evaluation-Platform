package com.propertyapp.privacy;

import com.propertyapp.audit.Audit;
import com.propertyapp.audit.AuditLog;
import com.propertyapp.privacy.ConsentService.ConsentException;
import com.propertyapp.privacy.ConsentService.Purpose;
import com.propertyapp.subscriptions.Access.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Privacy controls for the signed-in user: consents, a copy of their data, their account activity
 * and account deletion; plus the public retention policy.
 *
 * <pre>
 * GET  /api/v1/privacy/consents      current decisions per purpose
 * POST /api/v1/privacy/consents      {purpose, granted, source: settings|prompt}
 * GET  /api/v1/privacy/export        JSON download of the user's data (art. 15 / 20)
 * GET  /api/v1/privacy/activity      the user's last 50 audit events
 * POST /api/v1/privacy/erasure       {password}: deletes the account (art. 17)
 * GET  /api/v1/privacy/retention     public: how long each kind of data is kept
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/privacy")
class PrivacyController {

	private final ConsentService consents;
	private final PrivacyService privacy;
	private final RetentionService retention;
	private final AuditLog auditLog;
	private final Audit audit;

	PrivacyController(ConsentService consents, PrivacyService privacy, RetentionService retention, AuditLog auditLog, Audit audit) {
		this.consents = consents;
		this.privacy = privacy;
		this.retention = retention;
		this.auditLog = auditLog;
		this.audit = audit;
	}

	record ConsentView(String purpose, boolean granted, boolean required, String policyVersion, String source, Instant recordedAt) {}

	record Consents(String policyVersion, List<ConsentView> consents) {}

	@GetMapping("/consents")
	Consents consents(Caller caller) {
		var current = consents.current(caller.user().id());
		return new Consents(ConsentService.POLICY_VERSION, Arrays.stream(Purpose.values()).map(p -> {
			var c = current.get(p);
			return c == null ? new ConsentView(p.id(), false, p.requiredForAccount(), null, null, null)
					: new ConsentView(p.id(), c.granted(), p.requiredForAccount(), c.policyVersion(), c.source(), c.recordedAt());
		}).toList());
	}

	record Decision(@NotBlank String purpose, @NotNull Boolean granted, @Pattern(regexp = "settings|prompt") String source) {}

	@PostMapping("/consents")
	Consents decide(Caller caller, @Valid @RequestBody Decision d) {
		if (caller.viaApiKey()) throw new ConsentException(403, "Consent is given by the person, while logged in, not with an API key.");
		Purpose p = Purpose.of(d.purpose()).orElseThrow(() -> new ConsentException(400, "Unknown purpose."));
		consents.decide(caller.user().id(), p, d.granted(), d.source() == null ? "settings" : d.source());
		audit.success(caller, d.granted() ? "consent.grant" : "consent.withdraw", p.id(), Map.of("policyVersion", ConsentService.POLICY_VERSION));
		return consents(caller);
	}

	@GetMapping("/export")
	ResponseEntity<Map<String, Object>> export(Caller caller) {
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"my-data.json\"")
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(privacy.export(caller));
	}

	@GetMapping("/activity")
	List<Map<String, Object>> activity(Caller caller) {
		return auditLog.forUser(caller.user().id(), 50).stream().map(PrivacyService::activity).toList();
	}

	record ErasureRequest(@NotBlank String password) {}

	@PostMapping("/erasure")
	PrivacyService.Erasure erase(Caller caller, @Valid @RequestBody ErasureRequest r) {
		return privacy.erase(caller, r.password());
	}

	/** Public. */
	@GetMapping("/retention")
	Map<String, Object> retentionPolicy() {
		var p = retention.policy();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("uploadedPhotosAndFloorPlans", p.uploadedFileDays() + " days after upload");
		out.put("auditEvents", p.auditDays() + " days");
		out.put("usageRecords", p.usageMonths() + " months");
		out.put("paymentWebhookIds", p.billingEventDays() + " days");
		out.put("businessEnquiries", p.enquiryMonths() + " months");
		out.put("revokedApiKeys", p.revokedKeyDays() + " days after revocation");
		out.put("sessions", "until they expire (30 days) or you sign out");
		out.put("account", "until you delete it");
		return out;
	}
}
