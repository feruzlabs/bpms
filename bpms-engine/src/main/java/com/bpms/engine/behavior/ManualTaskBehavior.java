package com.bpms.engine.behavior;

import com.bpms.core.definition.ManualTaskNode;
import com.bpms.core.definition.SequenceFlow;

import java.util.List;

/**
 * manualTask: apply IO input mappings, then IO output mappings (no automated work in between), then
 * take the plain outgoing flows — migrated verbatim from the old-engine's two separate
 * {@code if (node instanceof ManualTaskNode)} blocks.
 */
public final class ManualTaskBehavior implements NodeBehavior<ManualTaskNode> {

    @Override
    public Class<ManualTaskNode> nodeType() {
        return ManualTaskNode.class;
    }

    @Override
    public NodeResult execute(ManualTaskNode manual, ExecutionContext ctx) {
        ctx.applyIoInputs(manual.inputs());
        ctx.applyIoOutputs(manual.outputs());
        List<SequenceFlow> outgoing = ctx.definition().outgoing(manual.id());
        return NodeResult.takeFlows(outgoing);
    }
}
