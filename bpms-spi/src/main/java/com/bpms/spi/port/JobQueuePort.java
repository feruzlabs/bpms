package com.bpms.spi.port;

import com.bpms.spi.engine.RuntimeModels.JobRecord;

/**
 * Pluggable async boundary for serviceTask work.
 * in-process: may run the handler synchronously before returning;
 * rabbit: publish only — consumer calls {@link JobHandler}.
 */
public interface JobQueuePort {
    void enqueue(JobRecord job);

    @FunctionalInterface
    interface JobHandler {
        void handle(JobRecord job);
    }
}
