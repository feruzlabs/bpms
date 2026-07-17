package com.bpms.server.service;

import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.port.InstanceControlPort;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orchestrates instance SUSPEND / RESUME / TERMINATE (plan 27 §6).
 *
 * <p>The heavy, transactional row mutations live in {@link InstanceControlPort} (one instance at a time).
 * This service adds the cross-cutting concerns the persistence layer must not own: cascade over the
 * subprocess tree, and re-enqueueing held jobs on resume (needs {@link JobQueuePort}).
 */
@Service
public class InstanceLifecycleService {

    private final InstanceControlPort control;
    private final JobRepositoryPort jobs;
    private final JobQueuePort jobQueue;

    public InstanceLifecycleService(InstanceControlPort control, JobRepositoryPort jobs, JobQueuePort jobQueue) {
        this.control = control;
        this.jobs = jobs;
        this.jobQueue = jobQueue;
    }

    @Transactional
    public void suspend(String instanceId, String user, String reason) {
        control.suspend(instanceId, user, reason);
    }

    /** SUSPENDED → RUNNING, then re-enqueue jobs that were held while suspended. */
    @Transactional
    public void resume(String instanceId) {
        control.resume(instanceId);
        for (JobRecord job : jobs.findPendingByInstance(instanceId)) {
            jobQueue.enqueue(job);
        }
    }

    /**
     * Absolute stop. When {@code cascade} is true, terminates the whole subprocess tree (root + descendants,
     * children before parents so no orphaned RUNNING child remains). Idempotent per instance.
     */
    @Transactional
    public void terminate(String instanceId, String user, String reason, boolean cascade) {
        if (!cascade) {
            control.terminate(instanceId, user, reason);
            return;
        }
        List<String> tree = control.instanceTree(instanceId);
        // reverse so descendants are terminated before their parents
        for (int i = tree.size() - 1; i >= 0; i--) {
            control.terminate(tree.get(i), user, reason);
        }
    }
}
