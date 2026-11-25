package com.propertyapp.users;

import com.propertyapp.users.AccountStore.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Accounts: registration, login, bearer sessions and API keys.
 *
 * <p>Passwords are stored as BCrypt hashes (cost 12). Session tokens and API keys are 256-bit random
 * values given to the client once; only their SHA-256 hash is stored. Repeated failed logins for an
 * email are slowed down by a lockout window.
 */
public class AccountService {

	public static final Duration SESSION_TTL = Duration.ofDays(30);
	static final int MAX_FAILED_LOGINS = 5;
	static final Duration LOCKOUT = Duration.ofMinutes(15);
	static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{2,}$");
	public static final String API_KEY_PREFIX = "pe_live_";

	private final AccountStore store;
	private final Clock clock;
	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
	private final SecureRandom random = new SecureRandom();
	private final Map<String, ArrayDeque<Instant>> failures = new ConcurrentHashMap<>();
	/** A real hash to compare against when the email is unknown, so timing does not reveal accounts. */
	private final String dummyHash = encoder.encode("not-a-real-password-" + UUID.randomUUID());

	public AccountService(AccountStore store, Clock clock) {
		this.store = store;
		this.clock = clock;
	}

	public record Session(String token, Instant expiresAt, User user) {}

	public static class AccountException extends RuntimeException {
		private final int status;

		public AccountException(int status, String message) {
			super(message);
			this.status = status;
		}

		public int status() {
			return status;
		}
	}

	public Session register(String email, String password, String fullName, String role) {
		String e = normaliseEmail(email);
		if (!EMAIL.matcher(e).matches()) throw new AccountException(400, "Enter a valid email address.");
		checkPassword(password);
		String name = fullName == null ? "" : fullName.trim();
		if (name.isEmpty() || name.length() > 120) throw new AccountException(400, "Enter your name (up to 120 characters).");
		String r = role == null ? "buyer" : role;
		if (!r.matches("buyer|investor|agent|valuer")) throw new AccountException(400, "Unknown role.");
		User user;
		try {
			user = store.createUser(e, encoder.encode(password), name, r);
		} catch (AccountStore.EmailTakenException ex) {
			throw new AccountException(409, "An account with this email already exists. Log in instead.");
		}
		return newSession(user);
	}

	public Session login(String email, String password) {
		String e = normaliseEmail(email);
		Instant now = clock.instant();
		ArrayDeque<Instant> recent = failures.computeIfAbsent(e, k -> new ArrayDeque<>());
		synchronized (recent) {
			while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(LOCKOUT))) recent.pollFirst();
			if (recent.size() >= MAX_FAILED_LOGINS) {
				throw new AccountException(429, "Too many failed attempts. Try again in 15 minutes.");
			}
		}
		Optional<User> user = store.userByEmail(e);
		boolean ok = encoder.matches(password == null ? "" : password, user.map(User::passwordHash).orElse(dummyHash)) && user.isPresent();
		if (!ok) {
			synchronized (recent) {
				recent.addLast(now);
			}
			throw new AccountException(401, "Email or password is incorrect.");
		}
		failures.remove(e);
		store.recordLogin(user.get().id(), now);
		return newSession(user.get());
	}

	/** Re-confirms the password before a sensitive action (account deletion). */
	public boolean passwordMatches(User user, String password) {
		return password != null && encoder.matches(password, user.passwordHash());
	}

	public void logout(String token) {
		if (token != null) store.deleteSession(sha256(token));
	}

	/** The user behind a bearer session token, if it is valid. */
	public Optional<User> userForToken(String token) {
		if (token == null || token.length() > 200) return Optional.empty();
		return store.sessionUser(sha256(token), clock.instant()).flatMap(store::userById);
	}

	/** The user behind an API key, if it is valid. */
	public Optional<User> userForApiKey(String key) {
		if (key == null || !key.startsWith(API_KEY_PREFIX) || key.length() > 200) return Optional.empty();
		return store.apiKeyOwner(sha256(key), clock.instant()).flatMap(store::userById);
	}

	public record NewApiKey(AccountStore.ApiKey key, String secret) {}

	public NewApiKey createApiKey(UUID ownerId, String name) {
		String n = name == null ? "" : name.trim();
		if (n.isEmpty() || n.length() > 80) throw new AccountException(400, "Name the key (up to 80 characters).");
		String secret = API_KEY_PREFIX + token();
		return new NewApiKey(store.createApiKey(ownerId, n, secret.substring(0, API_KEY_PREFIX.length() + 6), sha256(secret)), secret);
	}

	private Session newSession(User user) {
		String token = token();
		Instant expires = clock.instant().plus(SESSION_TTL);
		store.saveSession(sha256(token), user.id(), expires);
		return new Session(token, expires, user);
	}

	private String token() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static void checkPassword(String password) {
		if (password == null || password.length() < 10) throw new AccountException(400, "Use a password of at least 10 characters.");
		if (password.length() > 72) throw new AccountException(400, "Use a password of at most 72 characters.");
	}

	static String normaliseEmail(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}

	public static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
