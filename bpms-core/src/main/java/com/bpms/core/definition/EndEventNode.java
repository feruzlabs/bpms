package com.bpms.core.definition;

import java.util.List;
import java.util.Optional;

public record EndEventNode(
        String id,
        String name,
        Optional<EventDefinition> eventDefinition,
        Optional<MultiInstanceSpec> multiInstance,
        List<ListenerSpec> listeners
) implements FlowNode {

    /** True when the end event carries {@code terminateEventDefinition}. */
    public boolean isTerminate() {
        return eventDefinition.filter(TerminateEventDef.class::isInstance).isPresent();
    }
}
