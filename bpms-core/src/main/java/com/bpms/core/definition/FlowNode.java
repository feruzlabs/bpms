package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

/**
 * Sealed BPMN flow-node hierarchy covering old-engine import surface (plan 14 §1.1).
 */
public sealed interface FlowNode permits
        StartEventNode,
        EndEventNode,
        TaskNode,
        ServiceTaskNode,
        UserTaskNode,
        ScriptTaskNode,
        ManualTaskNode,
        SendTaskNode,
        ReceiveTaskNode,
        BusinessRuleTaskNode,
        CallActivityNode,
        ExclusiveGatewayNode,
        ParallelGatewayNode,
        InclusiveGatewayNode,
        SubProcessNode,
        BoundaryEventNode,
        IntermediateCatchEventNode,
        IntermediateThrowEventNode {

    String id();

    String name();

    Optional<MultiInstanceSpec> multiInstance();

    List<ListenerSpec> listeners();
}
