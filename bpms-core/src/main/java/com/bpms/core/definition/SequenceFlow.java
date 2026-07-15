package com.bpms.core.definition;

import java.util.Optional;

public record SequenceFlow(
        String id,
        String name,
        String sourceRef,
        String targetRef,
        Optional<ConditionExpr> condition
) {
}