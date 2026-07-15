package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record ManualTaskNode(
        String id,
        String name,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}