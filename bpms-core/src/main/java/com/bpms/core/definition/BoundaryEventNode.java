package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record BoundaryEventNode(
        String id,
        String name,
        String attachedToRef,
        boolean cancelActivity,
        Optional<EventDefinition> eventDefinition,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}