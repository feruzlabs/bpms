package com.bpms.engine;

import com.bpms.spi.port.TokenStatePort;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Used by the backward-compatible {@link ExecutionEngine} constructors that omit token-state tracking. */
public enum NoOpTokenStatePort implements TokenStatePort {
    INSTANCE;

    @Override
    public String enter(String tokenId, String instanceId, String nodeId, String nodeType, Instant enteredAt) {
        return UUID.randomUUID().toString();
    }

    @Override
    public void exit(String tokenStateId, String status, Instant exitedAt, Integer durationMs, String errorMessage) {
        // intentionally empty
    }

    @Override
    public Optional<String> activeStateId(String tokenId, String nodeId) {
        return Optional.empty();
    }
}
