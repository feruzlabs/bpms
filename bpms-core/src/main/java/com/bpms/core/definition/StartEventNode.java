package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record StartEventNode(
        String id,
        String name,
        Optional<EventDefinition> eventDefinition,
        Optional<FormDataSpec> formData,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}