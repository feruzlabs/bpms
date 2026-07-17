package com.bpms.spi.port;

import java.time.Instant;
import java.util.Optional;

/**
 * Per-visit node lifecycle (plan 23): insert ACTIVE on entry, update the same row to
 * COMPLETED/FAILED/CANCELED on exit. Unlike {@link TokenRepositoryPort}, this is insert-then-update,
 * not append-only — one row per node visit, ordered by {@code sequenceNo}.
 */
public interface TokenStatePort {

    /** Inserts an ACTIVE row for this node visit and returns its generated id. */
    String enter(String tokenId, String instanceId, String nodeId, String nodeType, Instant enteredAt);

    /** Updates the row created by {@link #enter} in place — never inserts a new row. */
    void exit(String tokenStateId, String status, Instant exitedAt, Integer durationMs, String errorMessage);

    /**
     * Looks up the still-ACTIVE row for this token+node. Needed when a node resumes on a different
     * call stack than the one that entered it — e.g. an async service-task job completing later.
     */
    Optional<String> activeStateId(String tokenId, String nodeId);
}
