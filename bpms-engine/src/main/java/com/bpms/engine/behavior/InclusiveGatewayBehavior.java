package com.bpms.engine.behavior;

import com.bpms.core.definition.InclusiveGatewayNode;
import com.bpms.core.definition.SequenceFlow;

import java.util.List;
import java.util.Map;

/**
 * inclusiveGateway: ALL true conditional flows are taken (fork); else {@code defaultFlowId}; else the
 * first unconditional flow (plan 22). The engine's generic {@code TakeFlows} multi-handling performs
 * the actual fork when this returns more than one flow — migrated verbatim.
 */
public final class InclusiveGatewayBehavior implements NodeBehavior<InclusiveGatewayNode> {

    @Override
    public Class<InclusiveGatewayNode> nodeType() {
        return InclusiveGatewayNode.class;
    }

    @Override
    public NodeResult execute(InclusiveGatewayNode gateway, ExecutionContext ctx) {
        Map<String, Object> vars = ctx.vars();
        List<SequenceFlow> outgoing = ctx.definition().outgoing(gateway.id());

        List<SequenceFlow> conditional = outgoing.stream()
                .filter(f -> f.condition().isPresent()
                        && ctx.expressions().evaluateLogic(f.condition().get().expression(), vars))
                .toList();

        List<SequenceFlow> chosen;
        if (!conditional.isEmpty()) {
            chosen = conditional;
        } else {
            SequenceFlow fallback = outgoing.stream()
                    .filter(f -> f.id().equals(gateway.defaultFlowId()))
                    .findFirst()
                    .or(() -> outgoing.stream()
                            .filter(f -> f.condition().isEmpty())
                            .findFirst())
                    .orElse(null);
            chosen = fallback == null ? List.of() : List.of(fallback);
        }
        return NodeResult.takeFlows(chosen);
    }
}
