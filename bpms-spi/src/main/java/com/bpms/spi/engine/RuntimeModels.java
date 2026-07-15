package com.bpms.spi.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RuntimeModels {
    private RuntimeModels() {}

    public enum InstanceStatus { RUNNING, WAITING, COMPLETED, FAILED }

    public enum TokenStatus { ACTIVE, WAITING, WAITING_JOB, COMPLETED, FAILED }

    public enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED }

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
            Instant endedAt
    ) {}

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
            boolean completed,
            Instant createdAt,
            Instant completedAt
    ) {}

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
