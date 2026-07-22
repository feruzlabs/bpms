package com.bpms.engine.behavior;

import com.bpms.core.definition.ScriptTaskNode;
import com.bpms.core.definition.SequenceFlow;

import java.util.List;
import java.util.Map;

/**
 * scriptTask: evaluate the script, optionally store {@code resultVariable}, then take the plain
 * outgoing flows. Intentionally does NOT refresh {@link ExecutionContext#vars()} after the store —
 * old-engine parity: the AFTER-listener phase evaluates against the pre-script vars snapshot
 * (existing quirk, preserved for behavior-preservation).
 */
public final class ScriptTaskBehavior implements NodeBehavior<ScriptTaskNode> {

    @Override
    public Class<ScriptTaskNode> nodeType() {
        return ScriptTaskNode.class;
    }

    @Override
    public NodeResult execute(ScriptTaskNode script, ExecutionContext ctx) {
        if (script.script() != null) {
            Object result = ctx.expressions().evaluate(script.script(), ctx.vars());
            if (script.resultVariable() != null && !script.resultVariable().isBlank()) {
                ctx.variables().putAll(ctx.token().instanceId(), Map.of(script.resultVariable(), result));
            }
        }
        List<SequenceFlow> outgoing = ctx.definition().outgoing(script.id());
        return NodeResult.takeFlows(outgoing);
    }
}
