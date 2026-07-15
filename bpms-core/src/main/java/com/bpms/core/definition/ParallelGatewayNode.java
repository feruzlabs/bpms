package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record ParallelGatewayNode(
        String id,
        String name,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}