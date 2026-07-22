package com.bpms.core.definition;

public sealed interface EventDefinition
        permits MessageEventDef, TimerEventDef, TerminateEventDef, UnsupportedEventDef {
}