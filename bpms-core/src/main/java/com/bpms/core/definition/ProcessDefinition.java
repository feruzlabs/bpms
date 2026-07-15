package com.bpms.core.definition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Format-agnostic process definition (pivot model for Camunda + future native format).
 */
public record ProcessDefinition(
        String id,
        String name,
        String processId,
        List<FlowNode> nodes,
        List<SequenceFlow> flows,
        List<MessageDef> messages,
        List<LaneSet> laneSets,
        Map<String, Object> metadata
) {
    public Optional<FlowNode> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }

    public List<SequenceFlow> outgoing(String nodeId) {
        return flows.stream().filter(f -> f.sourceRef().equals(nodeId)).toList();
    }
}
