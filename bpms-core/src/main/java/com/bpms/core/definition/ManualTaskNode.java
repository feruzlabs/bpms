package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record ManualTaskNode(
        String id,
        String name,
        List<IoParameter> inputs,
        List<IoParameter> outputs,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}
