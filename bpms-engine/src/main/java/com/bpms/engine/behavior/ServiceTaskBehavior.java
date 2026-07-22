package com.bpms.engine.behavior;

import com.bpms.core.definition.BoundaryEventNode;
import com.bpms.core.definition.ConnectorImplementation;
import com.bpms.core.definition.IoParameter;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.ServiceTaskNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * serviceTask (connector implementation): async → enqueue a job and WAIT; sync → execute the connector
 * then take the plain outgoing flows. Non-connector implementations are a no-op pass-through, matching
 * the old-engine's {@code if (... instanceof ConnectorImplementation ci)} guard.
 *
 * <p>Plan 32 Phase 3: on entry, registers any boundary timer/message/signal events attached to this
 * activity ({@code BoundarySupport}). On the SYNCHRONOUS path, if the activity finishes (success or
 * connector failure) the boundary subscriptions are cleared — the activity is done, nothing left to
 * interrupt. If a connector fails and an {@code errorEventDefinition} boundary is attached, the failure is
 * caught here and rerouted onto the boundary's own outgoing flows instead of failing the token.
 */
public final class ServiceTaskBehavior implements NodeBehavior<ServiceTaskNode> {

    @Override
    public Class<ServiceTaskNode> nodeType() {
        return ServiceTaskNode.class;
    }

    @Override
    public NodeResult execute(ServiceTaskNode service, ExecutionContext ctx) {
        BoundarySupport.registerBoundaryEvents(ctx, service.id());

        if (service.implementation() instanceof ConnectorImplementation ci) {
            Map<String, Object> inputs = new HashMap<>();
            for (IoParameter p : ci.binding().inputs()) {
                inputs.put(p.name(), ctx.expressions().evaluate(p.value(), ctx.vars()));
            }
            if (ctx.asyncServiceTasks()) {
                // Boundary subscriptions stay open until ExecutionEngine.continueAfterServiceTask clears
                // them (the connector job hasn't run yet — a boundary timer/message must still be able to fire).
                ctx.enqueueServiceTask(ci.binding().connectorId(), inputs);
                return NodeResult.waiting();
            }

            Optional<BoundaryEventNode> errorBoundary = BoundarySupport.findErrorBoundary(ctx.definition(), service.id());
            if (errorBoundary.isPresent()) {
                String error = ctx.tryExecuteConnector(ci.binding().connectorId(), inputs);
                BoundarySupport.clearBoundaryEvents(ctx, service.id());
                if (error != null) {
                    return BoundarySupport.takeErrorBoundaryFlow(ctx, errorBoundary.get());
                }
            } else {
                ctx.executeConnector(ci.binding().connectorId(), inputs);
                BoundarySupport.clearBoundaryEvents(ctx, service.id());
            }
        }
        List<SequenceFlow> outgoing = ctx.definition().outgoing(service.id());
        return NodeResult.takeFlows(outgoing);
    }
}
