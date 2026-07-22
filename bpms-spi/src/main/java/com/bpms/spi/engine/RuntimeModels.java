package com.bpms.spi.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RuntimeModels {
    private RuntimeModels() {}

    public enum InstanceStatus { RUNNING, WAITING, SUSPENDED, COMPLETED, FAILED, TERMINATED }

    public enum TokenStatus { ACTIVE, WAITING, WAITING_JOB, COMPLETED, FAILED, CANCELED }

    public enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELED }

    public record DeployResult(String definitionId, String key, int version) {}

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
            Instant createdAt
    ) {}

    public record InstanceRecord(
            String id,
            String definitionId,
            String businessKey,
            InstanceStatus status,
            Instant createdAt,
            Instant endedAt,
            String createdBy
    ) {
        /** Backward-compatible ctor without createdBy. */
        public InstanceRecord(
                String id, String definitionId, String businessKey,
                InstanceStatus status, Instant createdAt, Instant endedAt
        ) {
            this(id, definitionId, businessKey, status, createdAt, endedAt, null);
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
}
