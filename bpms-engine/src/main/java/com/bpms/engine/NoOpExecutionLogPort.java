package com.bpms.engine;

import com.bpms.spi.port.ExecutionLogPort;

import java.util.List;

/** Used when {@code bpms.execution-log.enabled=false}. */
public enum NoOpExecutionLogPort implements ExecutionLogPort {
    INSTANCE;

    @Override
    public void log(LogEntry entry) {
        // intentionally empty
    }

    @Override
    public List<LogEntry> byInstance(String instanceId) {
        return List.of();
    }
}
