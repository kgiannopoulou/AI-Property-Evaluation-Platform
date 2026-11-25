package com.propertyapp.audit;

import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.users.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes audit events. The client address of the current request is stored only as a salted
 * SHA-256 hash, which is enough to spot many failures from one address without keeping it. A
 * failure to write an event is logged and does not fail the user's request.
 */
public class Audit {

	private static final Logger log = LoggerFactory.getLogger(Audit.class);

	private final AuditLog events;
	private final Clock clock;
	private final String ipSalt;

	public Audit(AuditLog events, Clock clock, String ipSalt) {
		this.events = events;
		this.clock = clock;
		this.ipSalt = ipSalt;
	}

	/** A successful action by the caller. */
	public void success(Caller caller, String action, String target, Map<String, Object> details) {
		write(caller.user().id(), caller.viaApiKey() ? "api-key" : "user", action, "success", target, details);
	}

	public void denied(Caller caller, String action, String target, Map<String, Object> details) {
		write(caller.user().id(), caller.viaApiKey() ? "api-key" : "user", action, "denied", target, details);
	}

	public void user(UUID userId, String action, String outcome, String target, Map<String, Object> details) {
		write(userId, "user", action, outcome, target, details);
	}

	/** No signed-in user: failed sign-ins, webhooks. */
	public void anonymous(String action, String outcome, String target, Map<String, Object> details) {
		write(null, "anonymous", action, outcome, target, details);
	}

	public void system(String action, Map<String, Object> details) {
		write(null, "system", action, "success", null, details);
	}

	/** A target that would identify a person, e.g. an email, as {@code kind-sha256:<hash>}. */
	public static String hashed(String kind, String value) {
		return kind + "-sha256:" + AccountService.sha256(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
	}

	private void write(UUID actor, String kind, String action, String outcome, String target, Map<String, Object> details) {
		try {
			events.record(new AuditLog.Event(clock.instant(), actor, kind, action, outcome, target, ipHash(),
					details == null ? Map.of() : details));
		} catch (RuntimeException e) {
			log.error("Could not write audit event {} ({})", action, outcome, e);
		}
	}

	private String ipHash() {
		if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
		HttpServletRequest request = attrs.getRequest();
		String ip = request.getRemoteAddr();
		return ip == null ? null : AccountService.sha256(ipSalt + "|" + ip);
	}
}
