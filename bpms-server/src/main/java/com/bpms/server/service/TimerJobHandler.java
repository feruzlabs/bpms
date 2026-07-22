package com.bpms.server.service;

import com.bpms.engine.ExecutionEngine;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.JobStatus;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TypedJobHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Fires a {@code TIMER} job (plan 32 Phase 2): parses the payload's {@code subscriptionId} and delegates
 * to {@code ExecutionEngine.resumeEventSubscription}, which transparently routes to a plain intermediate
 * catch/{@code receiveTask} resume or — if the subscription's {@code configJson} marks it a boundary — to
 * {@code ExecutionEngine.fireBoundaryEvent}.
 *
 * <p>{@code JobDispatcher} already drops jobs for TERMINATED instances and leaves SUSPENDED ones PENDING
 * before routing here; this handler re-checks defensively so it stays correct even when invoked directly
 * (tests, or a future scheduled poller that doesn't go through the dispatcher).
 */
@Component
public class TimerJobHandler implements TypedJobHandler {

    public static final String TYPE = "TIMER";

    private final JobRepositoryPort jobs;
    private final InstanceRepositoryPort instances;
    private final ObjectProvider<ExecutionEngine> engine;
    private final ObjectMapper json;

    public TimerJobHandler(
            JobRepositoryPort jobs,
            InstanceRepositoryPort instances,
            ObjectProvider<ExecutionEngine> engine,
            ObjectMapper json
    ) {
        this.jobs = jobs;
        this.instances = instances;
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
        if (current.status() == JobStatus.COMPLETED || current.status() == JobStatus.CANCELED) {
            return; // idempotent
        }

        InstanceRecord instance = instances.findInstanceById(current.instanceId()).orElse(null);
        if (instance == null
                || instance.status() == InstanceStatus.TERMINATED
                || instance.status() == InstanceStatus.SUSPENDED) {
            jobs.save(new JobRecord(
                    current.id(), current.instanceId(), current.tokenId(), current.type(), current.payload(),
                    JobStatus.CANCELED, current.attempts(), current.runAt()));
            return;
        }

        jobs.save(new JobRecord(
                current.id(), current.instanceId(), current.tokenId(), current.type(), current.payload(),
                JobStatus.RUNNING, current.attempts() + 1, current.runAt()));

        try {
            Map<String, Object> payload = json.readValue(current.payload(), new TypeReference<>() {});
            String subscriptionId = String.valueOf(payload.get("subscriptionId"));

            engine.getObject().resumeEventSubscription(subscriptionId, Map.of());

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
