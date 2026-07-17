package com.bpms.spi.port;

import java.time.Instant;

/** One row per execution-listener invocation (plan 23) — a node can have several listeners per phase. */
public interface ListenerLogPort {

    void log(ListenerLogEntry entry);

    record ListenerLogEntry(
            String tokenStateId,
            String instanceId,
            String nodeId,
            String phase,        // BEFORE | AFTER
            int listenerIndex,
            String listenerType, // CLASS | EXPRESSION | DELEGATE_EXPRESSION
            String listenerRef,
            String status,       // SUCCESS | FAILED
            Instant startedAt,
            Instant endedAt,
            String errorMessage
    ) {}
}
