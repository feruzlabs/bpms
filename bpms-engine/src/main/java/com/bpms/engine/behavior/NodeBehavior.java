package com.bpms.engine.behavior;

import com.bpms.core.definition.FlowNode;

/**
 * Node-type-specific execution logic, extracted from {@code ExecutionEngine.run()}'s old
 * {@code if (node instanceof X)} chain (plan 33 Phase 0). One implementation per {@link FlowNode}
 * subtype; the engine stays a thin dispatcher and keeps loop/step-budget/cooperative-stop/token_state
 * trace/listener firing centralized.
 */
public interface NodeBehavior<N extends FlowNode> {

    /** The concrete {@link FlowNode} subtype this handler executes. */
    Class<N> nodeType();

    /** Executes {@code node}: waits, advances, forks, or finishes the token — see {@link NodeResult}. */
    NodeResult execute(N node, ExecutionContext ctx);
}
