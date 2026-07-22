package com.bpms.engine.behavior;

import com.bpms.core.definition.EventDefinition;
import com.bpms.core.definition.IntermediateThrowEventNode;
import com.bpms.core.definition.MessageEventDef;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.SignalEventDef;

import java.util.List;
import java.util.Map;

/**
 * {@code intermediateThrowEvent}: message throw correlates every open matching MESSAGE subscription;
 * signal throw broadcasts to every open matching SIGNAL subscription; {@code none}/unsupported is a plain
 * log point — the token always continues immediately after throwing (plan 32 Phase 3).
 */
public final class IntermediateThrowEventBehavior implements NodeBehavior<IntermediateThrowEventNode> {

    @Override
    public Class<IntermediateThrowEventNode> nodeType() {
        return IntermediateThrowEventNode.class;
    }

    @Override
    public NodeResult execute(IntermediateThrowEventNode event, ExecutionContext ctx) {
        EventDefinition def = event.eventDefinition().orElse(null);
        if (def instanceof MessageEventDef message) {
            String name = message.messageName() != null ? message.messageName() : message.messageRef();
            ctx.correlateMessage(name, Map.of());
            ctx.refreshVars();
        } else if (def instanceof SignalEventDef signal) {
            String name = signal.signalName() != null ? signal.signalName() : signal.signalRef();
            ctx.broadcastSignal(name, Map.of());
            ctx.refreshVars();
        }
        List<SequenceFlow> outgoing = ctx.definition().outgoing(event.id());
        return NodeResult.takeFlows(outgoing);
    }
}
