package com.bpms.server.service;

import com.bpms.spi.port.IncidentPort;
import com.bpms.spi.port.InstanceControlPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Proactive runaway/recursive-loop guardrail (plan 27 §4). Complements the engine's in-run step budget:
 * these checks look at persisted history across async boundaries (whole-instance revisit counts, subprocess
 * recursion depth, and how many instances a single root has spawned).
 *
 * <p>{@link #checkSpawnDepthBeforeStart} is called on the instance-creation path — wired via
 * {@code SpawnGuardPort} from {@code CallActivityBehavior} on every callActivity spawn (plan 34 Phase 1;
 * previously dormant per plan 29).
 */
@Component
public class RunawayGuard {

    private final InstanceControlPort control;
    private final IncidentPort incidents;
    private final int maxNodeRevisits;
    private final int maxSubprocessDepth;
    private final int maxSpawnPerRoot;
    private final int maxTokenStates;

    public RunawayGuard(
            InstanceControlPort control,
            IncidentPort incidents,
            @Value("${bpms.runaway.max-node-revisits:1000}") int maxNodeRevisits,
            @Value("${bpms.runaway.max-subprocess-depth:50}") int maxSubprocessDepth,
            @Value("${bpms.runaway.max-spawn-per-root:1000}") int maxSpawnPerRoot,
            @Value("${bpms.runaway.max-token-states:100000}") int maxTokenStates
    ) {
        this.control = control;
        this.incidents = incidents;
        this.maxNodeRevisits = maxNodeRevisits;
        this.maxSubprocessDepth = maxSubprocessDepth;
        this.maxSpawnPerRoot = maxSpawnPerRoot;
        this.maxTokenStates = maxTokenStates;
    }

    /** True when a node has been revisited too many times across the instance's whole history. */
    public boolean isNodeRevisitExceeded(String instanceId) {
        return control.maxNodeRevisitCount(instanceId) > maxNodeRevisits;
    }

    public boolean isTokenStateCapExceeded(String instanceId) {
        return control.tokenStateCount(instanceId) > maxTokenStates;
    }

    /**
     * Whole-instance caps checked on the async job path (across async boundaries), so a loop that keeps
     * re-enqueueing jobs for the same node is caught too. Opens a {@code LOOP_DETECTED} incident and returns
     * true — the caller must then SUSPEND the instance. Returns false when nothing is tripped.
     */
    public boolean tripInstanceCaps(String instanceId) {
        if (isNodeRevisitExceeded(instanceId)) {
            incidents.raise(instanceId, null, null, "LOOP_DETECTED", "ERROR",
                    "node revisit count exceeded " + maxNodeRevisits + " (async loop)");
            return true;
        }
        if (isTokenStateCapExceeded(instanceId)) {
            incidents.raise(instanceId, null, null, "LOOP_DETECTED", "ERROR",
                    "token-state count exceeded " + maxTokenStates);
            return true;
        }
        return false;
    }

    /**
     * Guard before spawning a child instance. Throws {@link RunawayBlockedException} (and opens an incident)
     * when recursion depth or the per-root spawn cap would be exceeded — this stops recursive spawn.
     */
    public void checkSpawnDepthBeforeStart(String parentInstanceId, String rootInstanceId) {
        if (parentInstanceId != null) {
            int depth = control.subprocessDepth(parentInstanceId) + 1;
            if (depth > maxSubprocessDepth) {
                incidents.raise(parentInstanceId, null, null, "SUBPROCESS_DEPTH_EXCEEDED", "ERROR",
                        "subprocess recursion depth " + depth + " > " + maxSubprocessDepth);
                throw new RunawayBlockedException(
                        "Subprocess depth cap exceeded (" + depth + " > " + maxSubprocessDepth + ")");
            }
        }
        if (rootInstanceId != null) {
            int spawned = control.spawnCountUnderRoot(rootInstanceId) + 1;
            if (spawned > maxSpawnPerRoot) {
                incidents.raise(rootInstanceId, null, null, "SPAWN_CAP_EXCEEDED", "ERROR",
                        "root " + rootInstanceId + " spawned " + spawned + " > " + maxSpawnPerRoot);
                throw new RunawayBlockedException(
                        "Spawn cap exceeded for root " + rootInstanceId + " (" + spawned + " > " + maxSpawnPerRoot + ")");
            }
        }
    }

    /** Thrown when a guardrail blocks a new instance/child from starting. */
    public static final class RunawayBlockedException extends RuntimeException {
        public RunawayBlockedException(String message) {
            super(message);
        }
    }
}
