package com.bpms.core.definition;

/**
 * BPMN {@code signalEventDefinition} — broadcast to every open subscription with the same name (unlike
 * {@link MessageEventDef}, which correlates to a single catcher) (plan 32 Phase 3).
 */
public record SignalEventDef(String signalRef, String signalName) implements EventDefinition {
}
