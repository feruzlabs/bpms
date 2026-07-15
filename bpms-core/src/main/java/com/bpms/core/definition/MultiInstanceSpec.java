package com.bpms.core.definition;

public record MultiInstanceSpec(
        boolean sequential,
        String loopCardinality,
        String collection,
        String elementVariable,
        String completionCondition
) {
}