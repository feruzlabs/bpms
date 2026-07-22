package com.bpms.engine.behavior;

import com.bpms.core.definition.BoundaryEventNode;
import com.bpms.core.definition.BusinessRuleTaskNode;
import com.bpms.core.definition.ConnectorImplementation;
import com.bpms.core.definition.IoParameter;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.expression.TemplateExpressions;
import com.bpms.spi.port.ExecutionLogPort.LogEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code businessRuleTask} (plan 32 Faza D). Full DMN evaluation is deferred to a future {@code bpms-dmn}
 * module. When the task carries a {@link ConnectorImplementation}, behaviour matches {@link ServiceTaskBehavior};
 * otherwise the token advances with a warning logged (no silent drop of an unimplemented DMN).
 */
public final class BusinessRuleTaskBehavior implements NodeBehavior<BusinessRuleTaskNode> {

    @Override
    public Class<BusinessRuleTaskNode> nodeType() {
        return BusinessRuleTaskNode.class;
    }

    @Override
    public NodeResult execute(BusinessRuleTaskNode task, ExecutionContext ctx) {
        BoundarySupport.registerBoundaryEvents(ctx, task.id());

        if (task.implementation() instanceof ConnectorImplementation ci) {
            Map<String, Object> inputs = new HashMap<>();
            for (IoParameter p : ci.binding().inputs()) {
                inputs.put(p.name(), TemplateExpressions.resolve(p.value(), ctx.expressions(), ctx.vars()));
            }
            if (ctx.asyncServiceTasks()) {
                ctx.enqueueServiceTask(ci.binding().connectorId(), inputs);
                return NodeResult.waiting();
            }

            Optional<BoundaryEventNode> errorBoundary = BoundarySupport.findErrorBoundary(ctx.definition(), task.id());
            if (errorBoundary.isPresent()) {
                String error = ctx.tryExecuteConnector(ci.binding().connectorId(), inputs);
                BoundarySupport.clearBoundaryEvents(ctx, task.id());
                if (error != null) {
                    return BoundarySupport.takeErrorBoundaryFlow(ctx, errorBoundary.get());
                }
            } else {
                ctx.executeConnector(ci.binding().connectorId(), inputs);
                BoundarySupport.clearBoundaryEvents(ctx, task.id());
            }
            ctx.refreshVars();
            return NodeResult.takeFlows(ctx.definition().outgoing(task.id()));
        }

        BoundarySupport.clearBoundaryEvents(ctx, task.id());
        ctx.execLog().log(new LogEntry(
                ctx.token().instanceId(), ctx.token().id(), task.id(), "businessRuleTask", null,
                "DMN_DEFERRED", "OK",
                "businessRuleTask without connector — DMN module not yet wired (plan 32 Faza D)",
                Map.of("nodeId", task.id()), null, ctx.clock().now()));
        List<SequenceFlow> outgoing = ctx.definition().outgoing(task.id());
        return NodeResult.takeFlows(outgoing);
    }
}
