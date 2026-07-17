package com.bpms.spi.port;

/** Raises operational incidents (failed job, connector error, runaway loop) — plan 25/27. */
public interface IncidentPort {

    /**
     * Opens an incident row and returns its id. {@code tokenId}/{@code tokenStateId} may be null when the
     * incident is instance-scoped (e.g. STEP_BUDGET_EXCEEDED raised from the token walker).
     */
    String raise(
            String instanceId,
            String tokenId,
            String tokenStateId,
            String type,
            String severity,
            String message);

    /** No-op default for tests / when incident tracking is disabled. */
    IncidentPort NOOP = (instanceId, tokenId, tokenStateId, type, severity, message) -> null;
}
