package com.bpms.spi.port;

/**
 * Cooperative stop signal the engine consults during a synchronous {@code run()} (plan 27 §3b).
 *
 * <p>Updating {@code process_instance.status} in the DB is not enough on its own: a tight synchronous
 * loop (A→B→A→B…) never crosses an async boundary, so the running thread would never re-read the DB.
 * The engine therefore asks this signal before every token transition. Implementations should be cheap
 * (a short-TTL cache over {@code SELECT status}), because it is called on the hot path.
 */
@FunctionalInterface
public interface TerminationSignal {

    /** True when the instance is SUSPENDED or TERMINATED and the engine must stop advancing its tokens. */
    boolean isHalted(String instanceId);

    /** Never halts — default for tests / callers that don't track lifecycle. */
    TerminationSignal NEVER = instanceId -> false;
}
