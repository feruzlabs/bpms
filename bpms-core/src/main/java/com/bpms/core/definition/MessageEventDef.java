package com.bpms.core.definition;

public record MessageEventDef(String messageRef, String messageName) implements EventDefinition {
}