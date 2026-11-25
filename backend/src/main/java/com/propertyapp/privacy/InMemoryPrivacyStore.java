package com.propertyapp.privacy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Development and tests only: forgets everything on restart. */
public class InMemoryPrivacyStore implements PrivacyStore {

	private final Map<UUID, List<Consent>> consents = new HashMap<>();
	private final Map<String, StoredFile> files = new LinkedHashMap<>();

	@Override
	public synchronized void recordConsent(UUID userId, Consent consent) {
		consents.computeIfAbsent(userId, k -> new ArrayList<>()).add(consent);
	}

	@Override
	public synchronized List<Consent> consentHistory(UUID userId) {
		return consents.getOrDefault(userId, List.of()).reversed();
	}

	@Override
	public synchronized void saveFile(StoredFile file) {
		files.put(file.key(), file);
	}

	@Override
	public synchronized Optional<StoredFile> file(String key) {
		return Optional.ofNullable(files.get(key));
	}

	@Override
	public synchronized List<StoredFile> filesOf(UUID ownerId) {
		return files.values().stream().filter(f -> f.ownerId().equals(ownerId)).toList();
	}

	@Override
	public synchronized List<StoredFile> expiredFiles(Instant now, int limit) {
		return files.values().stream().filter(f -> !f.deleteAfter().isAfter(now))
				.sorted(Comparator.comparing(StoredFile::deleteAfter)).limit(limit).toList();
	}

	@Override
	public synchronized void deleteFileRecord(String key) {
		files.remove(key);
	}

	@Override
	public Map<String, Long> purge(Instant now, RetentionPolicy policy) {
		// Sessions, usage and billing records of the in-memory account store end with the process.
		return Map.of();
	}

	@Override
	public synchronized void forget(UUID userId) {
		consents.remove(userId);
		files.values().removeIf(f -> f.ownerId().equals(userId));
	}
}
