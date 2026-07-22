package com.bpms.engine.behavior;

import com.bpms.core.definition.CallActivityNode;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.StartEventNode;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;

import java.util.List;
import java.util.UUID;

/**
 * callActivity (plan 34 Phase 1): spawns a child process instance, copies the parent's variables into it
 * (MVP — no {@code camunda:in}/{@code camunda:out} mapping yet), and runs it SYNCHRONOUSLY on this call
 * stack via {@link ExecutionContext#runSibling}.
 *
 * <ul>
 *   <li>If the child finishes within this synchronous call (common case: no wait points), the parent
 *       advances immediately — {@code TakeFlows} the callActivity's own outgoing flows, exactly like a
 *       serviceTask that completed synchronously.</li>
 *   <li>If the child is still RUNNING/WAITING (it hit a userTask or async serviceTask), the parent token
 *       also goes WAITING at the callActivity node. When the child instance eventually completes,
 *       {@code ExecutionEngine.maybeCompleteInstance} notices {@code InstanceRecord.parentInstanceId()} is
 *       set and calls {@code ExecutionEngine.resumeParentAfterCallActivity} to wake this parent token.</li>
 * </ul>
 */
public final class CallActivityBehavior implements NodeBehavior<CallActivityNode> {

    @Override
    public Class<CallActivityNode> nodeType() {
        return CallActivityNode.class;
    }

    @Override
    public NodeResult execute(CallActivityNode node, ExecutionContext ctx) {
        String calledElement = node.calledElement();
        ProcessDefinition childDef = ctx.definitionLookup().findDefinitionByKey(calledElement)
                .orElseThrow(() -> new IllegalStateException(
                        "callActivity " + node.id() + ": no deployed process found for calledElement=" + calledElement));
        String childDefId = ctx.definitionLookup().findDefinitionIdByKey(calledElement)
                .orElseThrow(() -> new IllegalStateException(
                        "callActivity " + node.id() + ": no deployed definition id for calledElement=" + calledElement));

        TokenRecord parentToken = ctx.token();
        String parentInstanceId = parentToken.instanceId();
        InstanceRecord parent = ctx.instances().findInstanceById(parentInstanceId).orElseThrow();
        String rootId = parent.rootInstanceId() != null ? parent.rootInstanceId() : parent.id();

        ctx.spawnGuard().checkBeforeSpawn(parentInstanceId, rootId);

        String childInstanceId = UUID.randomUUID().toString();
        ctx.instances().save(new InstanceRecord(
                childInstanceId, childDefId, parent.businessKey(), InstanceStatus.RUNNING,
                ctx.clock().now(), null, parent.createdBy(), parentInstanceId, rootId));
        ctx.variables().putAll(childInstanceId, ctx.vars());

        StartEventNode childStart = childDef.nodes().stream()
                .filter(StartEventNode.class::isInstance)
                .map(StartEventNode.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "callActivity " + node.id() + ": called process " + calledElement + " has no start event"));

        TokenRecord childToken = new TokenRecord(
                UUID.randomUUID().toString(), childInstanceId, childStart.id(), TokenStatus.ACTIVE, null);
        ctx.tokens().save(childToken);
        ctx.runSibling(childDef, childToken, parent.businessKey());

        InstanceRecord childAfter = ctx.instances().findInstanceById(childInstanceId).orElseThrow();
        if (childAfter.status() == InstanceStatus.COMPLETED) {
            ctx.variables().putAll(parentInstanceId, ctx.variables().getAll(childInstanceId));
            ctx.refreshVars();
            List<SequenceFlow> outgoing = ctx.definition().outgoing(node.id());
            return NodeResult.takeFlows(outgoing);
        }

        ctx.tokens().save(new TokenRecord(
                parentToken.id(), parentInstanceId, parentToken.currentNodeId(), TokenStatus.WAITING, null));
        ctx.instances().save(ctx.withStatus(parentInstanceId, InstanceStatus.WAITING));
        // Keep token_state ACTIVE until resumeParentAfterCallActivity (same convention as userTask WAITING).
        return NodeResult.waiting();
    }
}
