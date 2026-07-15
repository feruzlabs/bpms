package com.bpms.server.service;

import com.bpms.core.definition.ProcessDefinition;
import com.bpms.engine.ExecutionEngine;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.JobStatus;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.bpms.spi.port.DefinitionRegistry;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Completes SERVICE_TASK jobs for both in-process and Rabbit consumers. */
@Component
public class ServiceTaskJobHandler implements JobQueuePort.JobHandler {

    private final JobRepositoryPort jobs;
    private final TokenRepositoryPort tokens;
    private final InstanceRepositoryPort instances;
    private final DefinitionRegistry registry;
    private final ObjectProvider<ExecutionEngine> engine;
    private final ObjectMapper json;

    public ServiceTaskJobHandler(
            JobRepositoryPort jobs,
            TokenRepositoryPort tokens,
            InstanceRepositoryPort instances,
            DefinitionRegistry registry,
            ObjectProvider<ExecutionEngine> engine,
            ObjectMapper json
    ) {
        this.jobs = jobs;
        this.tokens = tokens;
        this.instances = instances;
        this.registry = registry;
        this.engine = engine;
        this.json = json;
    }

    @Override
    @Transactional
    public void handle(JobRecord job) {
        JobRecord current = jobs.findJobById(job.id()).orElse(job);
        if (current.status() == JobStatus.COMPLETED) {
            return; // idempotent
        }
        jobs.save(new JobRecord(
                current.id(), current.instanceId(), current.tokenId(), current.type(), current.payload(),
                JobStatus.RUNNING, current.attempts() + 1, current.runAt()));

        try {
            Map<String, Object> payload = json.readValue(current.payload(), new TypeReference<>() {});
            String connectorId = String.valueOf(payload.get("connectorId"));
            String businessKey = String.valueOf(payload.getOrDefault("businessKey", ""));
            @SuppressWarnings("unchecked")
            Map<String, Object> inputs = (Map<String, Object>) payload.getOrDefault("inputs", Map.of());

            TokenRecord token = tokens.findTokenById(current.tokenId()).orElseThrow();
            InstanceRecord instance = instances.findInstanceById(current.instanceId()).orElseThrow();
            ProcessDefinition model = registry.get(instance.definitionId());

            ExecutionEngine executionEngine = engine.getObject();
            executionEngine.executeConnector(token, businessKey, connectorId, inputs);
            jobs.save(new JobRecord(
                    current.id(), current.instanceId(), current.tokenId(), current.type(), current.payload(),
                    JobStatus.COMPLETED, current.attempts() + 1, current.runAt()));

            TokenRecord waiting = tokens.findTokenById(current.tokenId()).orElseThrow();
            if (waiting.status() == TokenStatus.WAITING_JOB || waiting.status() == TokenStatus.ACTIVE) {
                executionEngine.continueAfterServiceTask(model, waiting, businessKey);
            }
        } catch (RuntimeException e) {
            jobs.save(new JobRecord(
                    current.id(), current.instanceId(), current.tokenId(), current.type(), current.payload(),
                    JobStatus.FAILED, current.attempts() + 1, current.runAt()));
            throw e;
        } catch (Exception e) {
            jobs.save(new JobRecord(
                    current.id(), current.instanceId(), current.tokenId(), current.type(), current.payload(),
                    JobStatus.FAILED, current.attempts() + 1, current.runAt()));
            throw new IllegalStateException(e);
        }
    }
}
