package com.bpms.engine.behavior;

import com.bpms.core.definition.BoundaryEventNode;
import com.bpms.core.definition.BusinessRuleTaskNode;
import com.bpms.core.definition.CallActivityNode;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ExclusiveGatewayNode;
import com.bpms.core.definition.FlowNode;
import com.bpms.core.definition.InclusiveGatewayNode;
import com.bpms.core.definition.IntermediateCatchEventNode;
import com.bpms.core.definition.IntermediateThrowEventNode;
import com.bpms.core.definition.ManualTaskNode;
import com.bpms.core.definition.ParallelGatewayNode;
import com.bpms.core.definition.ReceiveTaskNode;
import com.bpms.core.definition.ScriptTaskNode;
import com.bpms.core.definition.SendTaskNode;
import com.bpms.core.definition.ServiceTaskNode;
import com.bpms.core.definition.StartEventNode;
import com.bpms.core.definition.SubProcessNode;
import com.bpms.core.definition.TaskNode;
import com.bpms.core.definition.UserTaskNode;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code Class<? extends FlowNode>} → {@link NodeBehavior} lookup (plan 33 Phase 0). {@link #defaults()}
 * wires the 9 node types the engine already executed before that refactor, plus {@code callActivity}/
 * {@code subProcess} (plan 34 Phase 1) and the event-infra element types (plan 32 Phases 2/3): {@code
 * sendTask}, {@code receiveTask}, {@code intermediateCatchEvent}, {@code intermediateThrowEvent}, and
 * {@code boundaryEvent} (registered as a plain {@link PassThroughBehavior} — a token only ever lands
 * directly on a boundary node when {@code ExecutionEngine.fireBoundaryEvent} places it there, and just
 * needs to take that node's own outgoing flows). {@code businessRuleTask} uses
 * {@link BusinessRuleTaskBehavior} (connector path or DMN-deferred pass-through; full DMN is a later module).
 */
public final class NodeBehaviorRegistry {

    private static final NodeBehavior<FlowNode> FALLBACK_PASS_THROUGH = new PassThroughBehavior<>(FlowNode.class);

    private final Map<Class<? extends FlowNode>, NodeBehavior<? extends FlowNode>> behaviors;

    public NodeBehaviorRegistry(Map<Class<? extends FlowNode>, NodeBehavior<? extends FlowNode>> behaviors) {
        this.behaviors = Map.copyOf(behaviors);
    }

    /** All migrated + plan-32 element handlers. */
    public static NodeBehaviorRegistry defaults() {
        Map<Class<? extends FlowNode>, NodeBehavior<? extends FlowNode>> map = new HashMap<>();
        register(map, new PassThroughBehavior<>(StartEventNode.class));
        register(map, new PassThroughBehavior<>(TaskNode.class));
        register(map, new EndEventBehavior());
        register(map, new UserTaskBehavior());
        register(map, new ManualTaskBehavior());
        register(map, new ServiceTaskBehavior());
        register(map, new ScriptTaskBehavior());
        register(map, new ExclusiveGatewayBehavior());
        register(map, new InclusiveGatewayBehavior());
        register(map, new ParallelGatewayBehavior());
        register(map, new CallActivityBehavior());
        register(map, new SubProcessBehavior());
        register(map, new SendTaskBehavior());
        register(map, new ReceiveTaskBehavior());
        register(map, new IntermediateCatchEventBehavior());
        register(map, new IntermediateThrowEventBehavior());
        register(map, new PassThroughBehavior<>(BoundaryEventNode.class));
        register(map, new BusinessRuleTaskBehavior());
        return new NodeBehaviorRegistry(map);
    }

    private static <N extends FlowNode> void register(
            Map<Class<? extends FlowNode>, NodeBehavior<? extends FlowNode>> map, NodeBehavior<N> behavior
    ) {
        map.put(behavior.nodeType(), behavior);
    }

    /** Returns the handler for this node's concrete type, or {@link PassThroughBehavior} if unmigrated. */
    @SuppressWarnings("unchecked")
    public NodeBehavior<FlowNode> get(FlowNode node) {
        NodeBehavior<? extends FlowNode> behavior = behaviors.get(node.getClass());
        return (NodeBehavior<FlowNode>) (behavior != null ? behavior : FALLBACK_PASS_THROUGH);
    }
}
