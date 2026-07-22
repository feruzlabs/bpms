package com.bpms.spi.port;

/**
 * Guardrail hook invoked right before a callActivity spawns a child process instance (plan 34 Phase 1).
 * Wraps {@code RunawayGuard.checkSpawnDepthBeforeStart} (plan 27 §4, previously dormant) behind an SPI port
 * so {@code bpms-engine} doesn't depend on {@code bpms-server}.
 */
public interface SpawnGuardPort {

    /** Throws (an unchecked exception) when spawning would exceed subprocess-depth or per-root spawn caps. */
    void checkBeforeSpawn(String parentInstanceId, String rootInstanceId);

    /** Default when the engine is built without this port wired (tests) — never blocks a spawn. */
    SpawnGuardPort NOOP = (parentInstanceId, rootInstanceId) -> {};
}
