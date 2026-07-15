package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record IntermediateCatchEventNode(
        String id,
        String name,
        Optional<EventDefinition> eventDefinition,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}