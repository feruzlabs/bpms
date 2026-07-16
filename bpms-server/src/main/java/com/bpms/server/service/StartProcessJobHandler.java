package com.bpms.server.service;

import com.bpms.core.definition.ProcessDefinition;
import com.bpms.engine.ExecutionEngine;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.JobStatus;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.port.DefinitionRegistry;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.TypedJobHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Consumer: runs engine from start token for PROCESS_START jobs. */
@Component
public class StartProcessJobHandler implements TypedJobHandler {

    public static final String TYPE = "PROCESS_START";

    private final JobRepositoryPort jobs;
    private final TokenRepositoryPort tokens;
    private final InstanceRepositoryPort instances;
    private final DefinitionRegistry registry;
    private final ObjectProvider<ExecutionEngine> engine;
    private final ObjectMapper json;

    public StartProcessJobHandler(
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
    public String type() {
        return TYPE;
    }

    @Override
    @Transactional
    public void handle(JobRecord job) {
        JobRecord current = jobs.findJobById(job.id()).orElse(job);
        if (current.status() == JobStatus.COMPLETED) {
            return;
        }
        jobs.save(new JobRecord(
                current.id(), current.instanceId(), current.tokenId(), current.type(), current.payload(),
                JobStatus.RUNNING, current.attempts() + 1, current.runAt()));

        try {
            Map<String, Object> payload = json.readValue(current.payload(), new TypeReference<>() {});
            String instanceId = String.valueOf(payload.get("instanceId"));
            String tokenId = String.valueOf(payload.get("tokenId"));
            String businessKey = String.valueOf(payload.getOrDefault("businessKey", ""));

            InstanceRecord inst = instances.findInstanceById(instanceId).orElseThrow();
            ProcessDefinition model = registry.get(inst.definitionId());
            TokenRecord token = tokens.findTokenById(tokenId).orElseThrow();

            engine.getObject().run(model, token, businessKey);

            jobs.save(new JobRecord(
                    current.id(), current.instanceId(), current.tokenId(), current.type(), current.payload(),
                    JobStatus.COMPLETED, current.attempts() + 1, current.runAt()));
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
