package com.bpms.core.definition;

/**
 * BPMN {@code errorEventDefinition} — used on a boundary event attached to an activity to catch a
 * connector/technical failure instead of letting it fail the token (plan 32 Phase 3).
 */
public record ErrorEventDef(String errorRef, String errorCode) implements EventDefinition {
}
