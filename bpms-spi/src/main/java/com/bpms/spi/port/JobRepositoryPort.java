package com.bpms.spi.port;

import com.bpms.spi.engine.RuntimeModels.JobRecord;

import java.util.List;
import java.util.Optional;

public interface JobRepositoryPort {
    JobRecord save(JobRecord job);

    Optional<JobRecord> findJobById(String id);

    /** PENDING jobs for an instance — used by resume() to re-enqueue held work (plan 27 §6). */
    List<JobRecord> findPendingByInstance(String instanceId);
}
