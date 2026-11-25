package com.propertyapp.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limit per key (e.g. client address), kept in memory. Used on registration and
 * sign-in on top of the per-email lockout. With several backend instances each one counts on its
 * own; a shared store (Redis) or the hosting proxy's limits would replace it there.
 */
public class RateLimiter {

	private final int max;
	private final Duration window;
	private final Clock clock;
	private final Map<String, ArrayDeque<Instant>> hits = new ConcurrentHashMap<>();

	public RateLimiter(int max, Duration window, Clock clock) {
		this.max = max;
		this.window = window;
		this.clock = clock;
	}

	/** Counts the attempt; false when the key has used up the window. */
	public boolean tryAcquire(String key) {
		Instant now = clock.instant();
		ArrayDeque<Instant> q = hits.computeIfAbsent(key == null ? "?" : key, k -> new ArrayDeque<>());
		synchronized (q) {
			while (!q.isEmpty() && !q.peekFirst().isAfter(now.minus(window))) q.pollFirst();
			if (q.size() >= max) return false;
			q.addLast(now);
		}
		if (hits.size() > 50_000) hits.entrySet().removeIf(e -> e.getValue().isEmpty());
		return true;
	}
}
