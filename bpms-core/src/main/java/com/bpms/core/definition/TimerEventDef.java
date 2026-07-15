package com.bpms.core.definition;

public record TimerEventDef(TimerKind kind, String value) implements EventDefinition {
}