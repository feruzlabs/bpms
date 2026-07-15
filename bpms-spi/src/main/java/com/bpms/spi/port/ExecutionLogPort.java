package com.bpms.spi.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Sync execution diagnostics stored with the business transaction. */
public interface ExecutionLogPort {

    void log(LogEntry entry);

    List<LogEntry> byInstance(String instanceId);

    default List<LogEntry> byInstance(String instanceId, String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return byInstance(instanceId);
        }
        return byInstance(instanceId).stream()
                .filter(e -> eventType.equals(e.eventType()))
                .toList();
    }

    record LogEntry(
            String instanceId,
            String tokenId,
            String nodeId,
            String nodeType,
            String connectorId,
            String eventType,
            String status,
            String message,
            Map<String, Object> details,
            Integer durationMs,
            Instant createdAt
    ) {}
}
