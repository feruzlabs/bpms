package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record ScriptTaskNode(
        String id,
        String name,
        String scriptFormat,
        String script,
        String scriptResource,
        String resultVariable,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}