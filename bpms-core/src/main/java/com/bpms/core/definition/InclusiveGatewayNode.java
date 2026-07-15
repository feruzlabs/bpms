package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record InclusiveGatewayNode(
        String id,
        String name,
        String defaultFlowId,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}