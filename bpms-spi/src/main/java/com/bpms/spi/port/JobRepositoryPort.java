package com.bpms.spi.port;

import com.bpms.spi.engine.RuntimeModels.JobRecord;

import java.util.Optional;

public interface JobRepositoryPort {
    JobRecord save(JobRecord job);

    Optional<JobRecord> findJobById(String id);
}
