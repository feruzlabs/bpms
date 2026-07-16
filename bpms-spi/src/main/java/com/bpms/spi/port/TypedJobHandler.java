package com.bpms.spi.port;

import com.bpms.spi.engine.RuntimeModels.JobRecord;

/** Type-specific job worker. Routed by {@code JobDispatcher} — not a direct {@link JobQueuePort.JobHandler} bean. */
public interface TypedJobHandler {

    /** e.g. {@code SERVICE_TASK}, {@code PROCESS_START} */
    String type();

    void handle(JobRecord job);
}
