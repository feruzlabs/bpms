package com.bpms.server.service;

import com.bpms.engine.ExecutionEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * {@code POST /api/v1/messages/correlate} backing service (plan 32 Phase 2/3) — thin wrapper over {@code
 * ExecutionEngine.correlateMessage} so the REST layer doesn't reach into the engine module directly.
 */
@Service
public class MessageCorrelationService {

    private final ObjectProvider<ExecutionEngine> engine;

    public MessageCorrelationService(ObjectProvider<ExecutionEngine> engine) {
        this.engine = engine;
    }

    /** @return how many open subscriptions were resumed. */
    @Transactional
    public int correlate(String messageName, Map<String, Object> variables) {
        return engine.getObject().correlateMessage(messageName, variables == null ? Map.of() : variables);
    }
}
