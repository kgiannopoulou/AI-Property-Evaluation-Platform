package com.propertyapp.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Development and tests only: forgets everything on restart. */
public class InMemoryAuditLog implements AuditLog {

	private final List<Event> events = new ArrayList<>();

	@Override
	public synchronized void record(Event event) {
		events.add(event);
	}

	@Override
	public synchronized List<Event> forUser(UUID userId, int limit) {
		return events.reversed().stream().filter(e -> userId.equals(e.actorUserId())).limit(limit).toList();
	}

	/** For tests. */
	public synchronized List<Event> all() {
		return List.copyOf(events);
	}

	@Override
	public synchronized void forget(UUID userId) {
		events.replaceAll(e -> userId.equals(e.actorUserId())
				? new Event(e.at(), null, e.actorKind(), e.action(), e.outcome(), e.target(), e.ipHash(), e.details())
				: e);
	}
}
