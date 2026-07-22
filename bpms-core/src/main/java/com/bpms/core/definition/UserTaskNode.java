package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record UserTaskNode(
        String id,
        String name,
        Optional<FormDataSpec> formData,
        String assignee,
        String candidateGroups,
        String candidateUsers,
        String dueDate,
        String priority,
        List<IoParameter> inputs,
        List<IoParameter> outputs,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {
}
