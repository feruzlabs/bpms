package com.bpms.engine.behavior;

import com.bpms.core.definition.ExclusiveGatewayNode;
import com.bpms.core.definition.SequenceFlow;

import java.util.List;
import java.util.Map;

/**
 * exclusiveGateway: first matching conditional flow; else {@code defaultFlowId}; else the first
 * unconditional flow (plan 22 — unconditional flows are treated as an implicit default). Logs the
 * chosen flow when one is found — migrated verbatim.
 */
public final class ExclusiveGatewayBehavior implements NodeBehavior<ExclusiveGatewayNode> {

    @Override
    public Class<ExclusiveGatewayNode> nodeType() {
        return ExclusiveGatewayNode.class;
    }

    @Override
    public NodeResult execute(ExclusiveGatewayNode gateway, ExecutionContext ctx) {
        Map<String, Object> vars = ctx.vars();
        List<SequenceFlow> outgoing = ctx.definition().outgoing(gateway.id());

        List<SequenceFlow> conditional = outgoing.stream()
                .filter(f -> f.condition().isPresent()
                        && ctx.expressions().evaluateLogic(f.condition().get().expression(), vars))
                .toList();

        List<SequenceFlow> chosen;
        boolean usedDefault;
        if (!conditional.isEmpty()) {
            chosen = List.of(conditional.getFirst());
            usedDefault = false;
        } else {
            SequenceFlow fallback = outgoing.stream()
                    .filter(f -> f.id().equals(gateway.defaultFlowId()))
                    .findFirst()
                    .or(() -> outgoing.stream()
                            .filter(f -> f.condition().isEmpty())
                            .findFirst())
                    .orElse(null);
            chosen = fallback == null ? List.of() : List.of(fallback);
            usedDefault = fallback != null;
        }

        if (!chosen.isEmpty()) {
            ctx.logGateway(gateway, chosen.getFirst(), conditional, usedDefault);
        }
        return NodeResult.takeFlows(chosen);
    }
}
