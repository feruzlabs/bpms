package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record CallActivityNode(
        String id,
        String name,
        String calledElement,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}