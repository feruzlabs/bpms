package com.bpms.persistence.jpa;

import com.bpms.spi.port.ExecutionLogPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "bpms.execution-log.enabled", havingValue = "true", matchIfMissing = true)
@Transactional
public class JpaExecutionLogAdapter implements ExecutionLogPort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JpaExecutionLogAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void log(LogEntry e) {
        try {
            String detailsJson = e.details() == null ? null : json.writeValueAsString(e.details());
            jdbc.update("""
                    insert into execution_log(
                      instance_id, token_id, node_id, node_type, connector_id,
                      event_type, status, message, details, duration_ms, created_at)
                    values(?,?,?,?,?,?,?,?,cast(? as jsonb),?,?)
                    """,
                    e.instanceId(), e.tokenId(), e.nodeId(), e.nodeType(), e.connectorId(),
                    e.eventType(), e.status(), e.message(), detailsJson, e.durationMs(),
                    Timestamp.from(e.createdAt()));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write execution_log", ex);
        }
    }

    @Override
    public List<LogEntry> byInstance(String instanceId) {
        return jdbc.query("""
                select instance_id, token_id, node_id, node_type, connector_id,
                       event_type, status, message, details::text, duration_ms, created_at
                from execution_log
                where instance_id=?
                order by created_at asc, id asc
                """, (r, n) -> new LogEntry(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6), r.getString(7), r.getString(8),
                parseDetails(r.getString(9)),
                (Integer) r.getObject(10),
                r.getTimestamp(11).toInstant()
        ), instanceId);
    }

    private Map<String, Object> parseDetails(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("raw", raw);
        }
    }
}
