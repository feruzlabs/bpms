package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record ServiceTaskNode(
        String id,
        String name,
        TaskImplementation implementation,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}