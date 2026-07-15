package com.bpms.core.definition;

import com.bpms.core.compat.CompatWarning;

import java.util.List;

public record ParseResult(
        ProcessDefinition definition,
        List<CompatWarning> warnings
) {
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
}
