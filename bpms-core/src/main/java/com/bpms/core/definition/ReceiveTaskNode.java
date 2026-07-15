package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record ReceiveTaskNode(
        String id,
        String name,
        String messageRef,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}