package com.bpms.engine.behavior;

import com.bpms.core.definition.ConnectorImplementation;
import com.bpms.core.definition.IoParameter;
import com.bpms.core.definition.SendTaskNode;
import com.bpms.core.definition.SequenceFlow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code sendTask} — the task-shaped form of a message throw. A {@link ConnectorImplementation} sendTask
 * delegates to the exact same connector execution logic as {@code ServiceTaskBehavior} (async → enqueue +
 * wait; sync → execute then take flows); a non-connector sendTask is a plain pass-through, since {@code
 * SendTaskNode} carries no message-ref of its own to correlate against (plan 32 Phase 3).
 */
public final class SendTaskBehavior implements NodeBehavior<SendTaskNode> {

    @Override
    public Class<SendTaskNode> nodeType() {
        return SendTaskNode.class;
    }

    @Override
    public NodeResult execute(SendTaskNode task, ExecutionContext ctx) {
        if (task.implementation() instanceof ConnectorImplementation ci) {
            Map<String, Object> inputs = new HashMap<>();
            for (IoParameter p : ci.binding().inputs()) {
                inputs.put(p.name(), ctx.expressions().evaluate(p.value(), ctx.vars()));
            }
            if (ctx.asyncServiceTasks()) {
                ctx.enqueueServiceTask(ci.binding().connectorId(), inputs);
                return NodeResult.waiting();
            }
            ctx.executeConnector(ci.binding().connectorId(), inputs);
        }
        List<SequenceFlow> outgoing = ctx.definition().outgoing(task.id());
        return NodeResult.takeFlows(outgoing);
    }
}
