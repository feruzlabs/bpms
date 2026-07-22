package com.bpms.spi.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RuntimeModels {
    private RuntimeModels() {}

    public enum InstanceStatus { RUNNING, WAITING, SUSPENDED, COMPLETED, FAILED, TERMINATED }

    public enum TokenStatus { ACTIVE, WAITING, WAITING_JOB, COMPLETED, FAILED, CANCELED }

    public enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELED }

    public record DeployResult(
            String definitionId,
            /** Process key ({@code process_definition.process_key}). */
            String processKey,
            int version,
            boolean isLatest,
            /** {@code false} when identical BPMN was redeployed and the existing version was reused (plan 35). */
            boolean changed,
            String checksum
    ) {
        /** Alias for {@link #processKey()} — older clients used {@code key}. */
        public String key() {
            return processKey;
        }
    }

    /** Catalog row for {@code GET /process-definitions?key=…} (plan 35). */
    public record DefinitionVersionView(
            String definitionId,
            String processKey,
            String name,
            int version,
            boolean isLatest,
            String checksum,
            Instant deployedAt,
            String status,
            long runningInstances
    ) {}

    public record TokenView(String id, String currentNodeId, TokenStatus status) {}

    public record InstanceView(
            String id,
            String definitionId,
            String businessKey,
            InstanceStatus status,
            List<TokenView> tokens,
            Map<String, Object> variables
    ) {}

    public record DefinitionRecord(
            String id,
            String key,
            String name,
            int version,
            String sourceFormat,
            String bpmnXml,
            Instant createdAt,
            String checksum,
            boolean isLatest
    ) {
        /** Backward-compatible ctor without checksum / isLatest. */
        public DefinitionRecord(
                String id, String key, String name, int version,
                String sourceFormat, String bpmnXml, Instant createdAt
        ) {
            this(id, key, name, version, sourceFormat, bpmnXml, createdAt, null, true);
        }
    }

    public record InstanceRecord(
            String id,
            String definitionId,
            String businessKey,
            InstanceStatus status,
            Instant createdAt,
            Instant endedAt,
            String createdBy,
            /** Plan 34 Phase 1: set when this instance was spawned by a callActivity — its parent's instance id. */
            String parentInstanceId,
            /** The top-of-chain ancestor instance id (== id when this instance has no parent). */
            String rootInstanceId,
            /** Plan 35: denorm of {@code process_definition.process_key} for join-free monitoring. */
            String definitionKey,
            /** Plan 35: denorm of {@code process_definition.version}. */
            Integer definitionVersion
    ) {
        /** Backward-compatible ctor without parent/root/denorm. */
        public InstanceRecord(
                String id, String definitionId, String businessKey,
                InstanceStatus status, Instant createdAt, Instant endedAt, String createdBy
        ) {
            this(id, definitionId, businessKey, status, createdAt, endedAt, createdBy, null, null, null, null);
        }

        /** Backward-compatible ctor without createdBy/parent/root/denorm. */
        public InstanceRecord(
                String id, String definitionId, String businessKey,
                InstanceStatus status, Instant createdAt, Instant endedAt
        ) {
            this(id, definitionId, businessKey, status, createdAt, endedAt, null);
        }

        /** Parent/root without denorm (callActivity path before plan 35 denorm was filled). */
        public InstanceRecord(
                String id, String definitionId, String businessKey,
                InstanceStatus status, Instant createdAt, Instant endedAt, String createdBy,
                String parentInstanceId, String rootInstanceId
        ) {
            this(id, definitionId, businessKey, status, createdAt, endedAt, createdBy,
                    parentInstanceId, rootInstanceId, null, null);
        }
    }

    public record TokenRecord(
            String id,
            String instanceId,
            String currentNodeId,
            TokenStatus status,
            String parentMultiInstanceId
    ) {}

    public record UserTaskRecord(
            String id,
            String instanceId,
            String tokenId,
            String nodeId,
            String name,
            String assignee,
            List<String> candidateGroups,
            List<String> candidateUsers,
            Instant dueDate,
            int priority,
            String formKey,
            String submittedData,
            Instant claimTime,
            boolean completed,
            Instant createdAt,
            Instant completedAt
    ) {
        /** Minimal ctor used by older stubs / tests. */
        public UserTaskRecord(
                String id, String instanceId, String tokenId, String nodeId, String name,
                boolean completed, Instant createdAt, Instant completedAt
        ) {
            this(id, instanceId, tokenId, nodeId, name,
                    null, List.of(), List.of(), null, 50, null, null, null,
                    completed, createdAt, completedAt);
        }
    }
    public record JobRecord(
            String id,
            String instanceId,
            String tokenId,
            String type,
            String payload,
            JobStatus status,
            int attempts,
            Instant runAt
    ) {}

    /**
     * A pending TIMER/MESSAGE/SIGNAL wait registered against {@code event_subscription} (plan 32 Phases 2/3).
     * {@code tokenId} is the token waiting for this event — for a plain intermediate catch/receiveTask that
     * token is parked AT {@code nodeId}; for a boundary event it is still parked at the attached activity and
     * {@code nodeId} holds the activity id (so {@link com.bpms.spi.port.EventSubscriptionPort#deleteByInstanceAndNode}
     * can clear every boundary subscription for that activity in one call) — the boundary event's OWN node id
     * and interrupting flag are then carried in {@code configJson} (see {@code BoundarySupport}).
     */
    public record EventSubscriptionRecord(
            String id,
            String instanceId,
            String tokenId,
            /** TIMER | MESSAGE | SIGNAL */
            String type,
            String eventName,
            String nodeId,
            String configJson,
            Instant createdAt
    ) {}
}
