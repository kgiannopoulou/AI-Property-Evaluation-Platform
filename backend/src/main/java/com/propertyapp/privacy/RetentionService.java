package com.propertyapp.privacy;

import com.propertyapp.audit.Audit;
import com.propertyapp.inspection.PhotoStorage;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Applies the retention policy every night: uploaded files after their deletion date (bytes first,
 * then the record), then the database rules in purge_expired_data().
 */
public class RetentionService {

	private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
	static final int FILE_BATCH = 500;

	private final PrivacyStore store;
	private final PhotoStorage storage;
	private final PrivacyStore.RetentionPolicy policy;
	private final Audit audit;
	private final Clock clock;

	public RetentionService(PrivacyStore store, PhotoStorage storage, PrivacyStore.RetentionPolicy policy, Audit audit, Clock clock) {
		this.store = store;
		this.storage = storage;
		this.policy = policy;
		this.audit = audit;
		this.clock = clock;
	}

	public PrivacyStore.RetentionPolicy policy() {
		return policy;
	}

	@Scheduled(cron = "${app.retention.cron:0 30 3 * * *}", zone = "UTC")
	public void scheduled() {
		try {
			Map<String, Long> counts = run();
			log.info("Retention purge: {}", counts);
		} catch (RuntimeException e) {
			log.error("Retention purge failed", e);
		}
	}

	public Map<String, Long> run() {
		Instant now = clock.instant();
		long files = 0;
		while (true) {
			var batch = store.expiredFiles(now, FILE_BATCH);
			for (var f : batch) {
				storage.delete(f.key());
				files++;
			}
			if (batch.size() < FILE_BATCH) break;
		}
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put("uploaded-files", files);
		counts.putAll(store.purge(now, policy));
		audit.system("retention.purge", Map.copyOf(counts));
		return counts;
	}
}
