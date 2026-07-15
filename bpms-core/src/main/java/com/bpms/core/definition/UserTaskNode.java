package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record UserTaskNode(
        String id,
        String name,
        Optional<FormDataSpec> formData,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}