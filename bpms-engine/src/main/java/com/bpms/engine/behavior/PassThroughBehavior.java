package com.bpms.engine.behavior;

import com.bpms.core.definition.FlowNode;
import com.bpms.core.definition.SequenceFlow;

import java.util.List;

/**
 * startEvent / plain task / any not-yet-migrated node type (plan 32 elements): no node-specific work,
 * just take the plain outgoing flows — matches the old-engine's implicit fallthrough for node types
 * that had no matching {@code if (node instanceof X)} block.
 */
public final class PassThroughBehavior<N extends FlowNode> implements NodeBehavior<N> {

    private final Class<N> type;

    public PassThroughBehavior(Class<N> type) {
        this.type = type;
    }

    @Override
    public Class<N> nodeType() {
        return type;
    }

    @Override
    public NodeResult execute(N node, ExecutionContext ctx) {
        List<SequenceFlow> outgoing = ctx.definition().outgoing(node.id());
        return NodeResult.takeFlows(outgoing);
    }
}
