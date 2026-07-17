package com.bpms.engine;

import com.bpms.spi.port.ListenerLogPort;

/** Used by the backward-compatible {@link ExecutionEngine} constructors that omit listener tracking. */
public enum NoOpListenerLogPort implements ListenerLogPort {
    INSTANCE;

    @Override
    public void log(ListenerLogEntry entry) {
        // intentionally empty
    }
}
