package com.bpms.engine.behavior;

import com.bpms.core.definition.SequenceFlow;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;

import java.util.List;

/**
 * Outcome of {@link NodeBehavior#execute}, interpreted centrally by {@code ExecutionEngine.run()}
 * (plan 33 Phase 0 — behavior-preserving NodeBehavior refactor). Mirrors the control-flow shapes the
 * old inline {@code run()} loop used ({@code return}, fall-through-then-advance, fork-then-recurse),
 * now made explicit as a result type instead of scattered {@code if (node instanceof X)} blocks.
 */
public sealed interface NodeResult {

    /** Stop the run() loop — token is WAITING for external completion (userTask, async serviceTask, unfinished parallel join). */
    record Waiting() implements NodeResult {}

    /** Stop the run() loop — the behavior already closed the token/instance itself (endEvent, terminateEndEvent, exhausted parallel join). */
    record Finished() implements NodeResult {}

    /** Continue the loop with a caller-prepared token; the behavior already called {@code completeNodeState()} itself. */
    record Continue(TokenRecord next) implements NodeResult {}

    /** Fork: continue with {@code next} on this call stack, then run each sibling to completion (parallel gateway fork). */
    record ContinueWithSiblings(TokenRecord next, List<TokenRecord> siblings) implements NodeResult {}

    /** The behavior computed which flows to take; the engine completes node state and advances/closes/forks generically. */
    record TakeFlows(List<SequenceFlow> flows) implements NodeResult {}

    static NodeResult waiting() {
        return new Waiting();
    }

    static NodeResult finished() {
        return new Finished();
    }

    static NodeResult continueWith(TokenRecord next) {
        return new Continue(next);
    }

    static NodeResult continueWithSiblings(TokenRecord next, List<TokenRecord> siblings) {
        return new ContinueWithSiblings(next, siblings);
    }

    static NodeResult takeFlows(List<SequenceFlow> flows) {
        return new TakeFlows(flows);
    }
}
