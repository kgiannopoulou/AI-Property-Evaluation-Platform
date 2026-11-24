package com.propertyapp.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.propertyapp.users.AccountService.AccountException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

	final InMemoryAccountStore store = new InMemoryAccountStore();
	final MutableClock clock = new MutableClock();
	final AccountService accounts = new AccountService(store, clock);

	static class MutableClock extends Clock {
		Instant now = Instant.parse("2026-11-24T10:00:00Z");

		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		public Instant instant() {
			return now;
		}
	}

	@Test
	void registerLoginAndTokens() {
		var s = accounts.register("  Maria@Example.TEST ", "correct horse battery", "Maria", null);
		assertThat(s.user().email()).isEqualTo("maria@example.test");
		assertThat(s.user().role()).isEqualTo("buyer");
		assertThat(s.user().passwordHash()).startsWith("$2a$12$").doesNotContain("correct horse");
		assertThat(accounts.userForToken(s.token())).contains(s.user());

		var again = accounts.login("maria@example.test", "correct horse battery");
		assertThat(again.token()).isNotEqualTo(s.token());
		accounts.logout(again.token());
		assertThat(accounts.userForToken(again.token())).isEmpty();
		assertThat(accounts.userForToken(s.token())).isPresent();
	}

	@Test
	void sessionsExpire() {
		var s = accounts.register("a@example.test", "correct horse battery", "A", "investor");
		clock.now = clock.now.plus(AccountService.SESSION_TTL).plusSeconds(1);
		assertThat(accounts.userForToken(s.token())).isEmpty();
	}

	@Test
	void rejectsBadInput() {
		assertThatThrownBy(() -> accounts.register("not-an-email", "correct horse battery", "A", null)).hasMessageContaining("valid email");
		assertThatThrownBy(() -> accounts.register("b@example.test", "short", "A", null)).hasMessageContaining("10 characters");
		assertThatThrownBy(() -> accounts.register("b@example.test", "correct horse battery", " ", null)).hasMessageContaining("name");
		accounts.register("c@example.test", "correct horse battery", "C", null);
		assertThatThrownBy(() -> accounts.register("C@example.test", "correct horse battery", "C", null))
				.isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.status()).isEqualTo(409));
	}

	@Test
	void sameMessageForUnknownEmailAndWrongPasswordThenLockout() {
		accounts.register("d@example.test", "correct horse battery", "D", null);
		assertThatThrownBy(() -> accounts.login("nobody@example.test", "whatever"))
				.hasMessage("Email or password is incorrect.");
		for (int i = 0; i < AccountService.MAX_FAILED_LOGINS; i++) {
			assertThatThrownBy(() -> accounts.login("d@example.test", "wrong password"))
					.isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.status()).isEqualTo(401));
		}
		assertThatThrownBy(() -> accounts.login("d@example.test", "correct horse battery"))
				.isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.status()).isEqualTo(429));
		clock.now = clock.now.plus(AccountService.LOCKOUT).plus(Duration.ofSeconds(1));
		assertThat(accounts.login("d@example.test", "correct horse battery").token()).isNotBlank();
	}

	@Test
	void apiKeysAreShownOnceStoredHashedAndRevocable() {
		var s = accounts.register("e@example.test", "correct horse battery", "E", null);
		var key = accounts.createApiKey(s.user().id(), "CRM import");
		assertThat(key.secret()).startsWith(AccountService.API_KEY_PREFIX);
		assertThat(key.key().prefix()).isEqualTo(key.secret().substring(0, AccountService.API_KEY_PREFIX.length() + 6));
		assertThat(accounts.userForApiKey(key.secret())).contains(s.user());
		assertThat(accounts.userForApiKey(key.secret() + "x")).isEmpty();
		assertThat(store.revokeApiKey(s.user().id(), key.key().id(), clock.now)).isTrue();
		assertThat(accounts.userForApiKey(key.secret())).isEmpty();
	}
}
