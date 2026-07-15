package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record SubProcessNode(
        String id,
        String name,
        List<FlowNode> childNodes,
        List<SequenceFlow> childFlows,
        List<LaneSet> childLaneSets,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}