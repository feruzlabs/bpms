package com.bpms.server.service;

import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.JobStatus;
import com.bpms.spi.port.InstanceControlPort;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TypedJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Single {@link JobQueuePort.JobHandler} bean — routes by {@link JobRecord#type()}.
 *
 * <p>Cooperative stop (plan 27 §3a): before routing, checks the owning instance. If it is TERMINATED the
 * job is ack-and-dropped (marked CANCELED, never executed); if SUSPENDED the job is left PENDING so
 * {@code resume()} can re-enqueue it later. This is how a queued "next step" is prevented from running
 * after an operator stops the instance.
 */
@Component
@Primary
public class JobDispatcher implements JobQueuePort.JobHandler {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    private final Map<String, TypedJobHandler> byType;
    private final InstanceControlPort control;
    private final JobRepositoryPort jobs;
    private final RunawayGuard guard;

    public JobDispatcher(
            List<TypedJobHandler> handlers, InstanceControlPort control, JobRepositoryPort jobs, RunawayGuard guard) {
        this.control = control;
        this.jobs = jobs;
        this.guard = guard;
        this.byType = handlers.stream()
                .collect(Collectors.toMap(TypedJobHandler::type, Function.identity(), (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate TypedJobHandler for type: " + a.type());
                }));
    }

    @Override
    public void handle(JobRecord job) {
        String status = control.statusOf(job.instanceId());
        if ("TERMINATED".equals(status)) {
            // ack-and-drop: do not run, do not enqueue the next step
            jobs.save(new JobRecord(
                    job.id(), job.instanceId(), job.tokenId(), job.type(), job.payload(),
                    JobStatus.CANCELED, job.attempts(), job.runAt()));
            log.info("Dropping job {} — instance {} is TERMINATED", job.id(), job.instanceId());
            return;
        }
        if ("SUSPENDED".equals(status)) {
            // leave the job PENDING; resume() re-enqueues held work
            log.info("Skipping job {} — instance {} is SUSPENDED", job.id(), job.instanceId());
            return;
        }

        // async-boundary runaway detection (plan 27 §4): a loop that keeps enqueueing jobs for the same
        // node is caught here and the instance is SUSPENDED for inspection.
        if (guard.tripInstanceCaps(job.instanceId())) {
            control.suspend(job.instanceId(), "system", "runaway loop detected (async)");
            log.warn("Suspending instance {} — runaway loop detected on async path", job.instanceId());
            return;
        }

        TypedJobHandler h = byType.get(job.type());
        if (h == null) {
            throw new IllegalStateException("No handler for job type: " + job.type());
        }
        h.handle(job);
    }
}
