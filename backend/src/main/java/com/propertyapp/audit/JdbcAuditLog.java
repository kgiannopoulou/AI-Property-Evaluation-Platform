package com.propertyapp.audit;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** audit_events (V9): inserts only; the table's trigger rejects updates and deletes. */
public class JdbcAuditLog implements AuditLog {

	private static final JsonMapper JSON = JsonMapper.shared();
	private final JdbcTemplate jdbc;

	public JdbcAuditLog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void record(Event e) {
		jdbc.update("""
				INSERT INTO audit_events (occurred_at, actor_user_id, actor_kind, action, outcome, target, ip_hash, details)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
				""", Timestamp.from(e.at()), e.actorUserId(), e.actorKind(), e.action(), e.outcome(), e.target(), e.ipHash(),
				JSON.writeValueAsString(e.details() == null ? Map.of() : e.details()));
	}

	@Override
	public List<Event> forUser(UUID userId, int limit) {
		return jdbc.query("""
				SELECT * FROM audit_events WHERE actor_user_id = ? ORDER BY occurred_at DESC, id DESC LIMIT ?
				""", (rs, i) -> new Event(rs.getTimestamp("occurred_at").toInstant(), rs.getObject("actor_user_id", UUID.class),
				rs.getString("actor_kind"), rs.getString("action"), rs.getString("outcome"), rs.getString("target"),
				rs.getString("ip_hash"), JSON.readValue(rs.getString("details"), new TypeReference<Map<String, Object>>() {})),
				userId, limit);
	}
}
