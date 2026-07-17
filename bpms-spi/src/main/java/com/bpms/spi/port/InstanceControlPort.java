package com.bpms.spi.port;

import java.util.List;

/**
 * Lifecycle control for a running instance (plan 27): suspend / resume / terminate plus the read-side
 * guardrail counters used to detect runaway/recursive loops.
 *
 * <p>{@link #terminate} and {@link #suspend} are transactional and idempotent — they close every child
 * entity (tokens, open token-states, queued jobs, event subscriptions, external tasks, open user-tasks,
 * open incidents) and write an audit log entry.
 */
public interface InstanceControlPort {

    /** Current {@code process_instance.status}, or {@code null} if the instance is unknown. */
    String statusOf(String instanceId);

    /** True when the instance is SUSPENDED or TERMINATED (cooperative stop for the engine/consumer). */
    boolean isHalted(String instanceId);

    /** Root + all descendant instance ids (recursive over {@code parent_instance_id}). */
    List<String> instanceTree(String rootId);

    /** Absolute, irreversible stop: instance TERMINATED, all children CANCELED/closed. Idempotent. */
    void terminate(String instanceId, String user, String reason);

    /** Reversible pause: instance SUSPENDED, queued jobs held. Idempotent. */
    void suspend(String instanceId, String user, String reason);

    /** Undo a suspend: SUSPENDED → RUNNING, re-enqueue held jobs. */
    void resume(String instanceId);

    // --- runaway guardrail read side (plan 27 §4) ---

    /** Highest visit count for any single node within the instance (loop detector). */
    int maxNodeRevisitCount(String instanceId);

    /** Total execution_token_state rows for the instance (overall step cap). */
    int tokenStateCount(String instanceId);

    /** Length of the {@code parent_instance_id} ancestor chain (recursive subprocess depth). */
    int subprocessDepth(String instanceId);

    /** Number of instances sharing this {@code root_instance_id} (spawn cap). */
    int spawnCountUnderRoot(String rootId);
}
