package com.propertyapp.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.propertyapp.audit.Audit;
import com.propertyapp.audit.InMemoryAuditLog;
import com.propertyapp.inspection.InspectionProperties;
import com.propertyapp.inspection.PhotoStorage;
import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.PlanCatalog;
import com.propertyapp.users.AccountStore.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionServiceTest {

	static class MutableClock extends Clock {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");

		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		public Clock withZone(ZoneId zone) {
			return this;
		}

		public Instant instant() {
			return now;
		}
	}

	@TempDir
	Path dir;

	@Test
	void uploadsAreDeletedAfterTheRetentionPeriodBytesAndRecord() throws Exception {
		var clock = new MutableClock();
		var store = new InMemoryPrivacyStore();
		var props = new InspectionProperties("http://localhost:8090", "", Duration.ofMinutes(1), dir.toString());
		var storage = new PhotoStorage(props, store, clock, 180);
		var log = new InMemoryAuditLog();
		var policy = new PrivacyStore.RetentionPolicy(180, 365, 24, 90, 24, 90);
		var retention = new RetentionService(store, storage, policy, new Audit(log, clock, "salt"), clock);

		var user = new User(UUID.randomUUID(), "r@example.test", "x", "R", "buyer", clock.now);
		var caller = new Caller(user, user.id(), PlanCatalog.free(), null, 2, false);
		String old = storage.save(caller, "p1", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, "jpg", "inspection-photo");
		clock.now = clock.now.plus(Duration.ofDays(100));
		String recent = storage.save(caller, "p1", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 2}, "jpg", "inspection-photo");

		clock.now = clock.now.plus(Duration.ofDays(79));
		assertThat(retention.run()).containsEntry("uploaded-files", 0L);

		clock.now = clock.now.plus(Duration.ofDays(2)); // 181 days after the first upload
		assertThat(retention.run()).containsEntry("uploaded-files", 1L);
		assertThat(Files.exists(dir.resolve(old))).isFalse();
		assertThat(store.file(old)).isEmpty();
		assertThat(Files.exists(dir.resolve(recent))).isTrue();
		assertThat(store.file(recent)).isPresent();
		assertThat(log.all()).anyMatch(e -> e.action().equals("retention.purge") && e.actorKind().equals("system"));
	}
}
