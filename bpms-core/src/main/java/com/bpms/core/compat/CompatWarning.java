package com.bpms.core.compat;

/**
 * Emitted when an element/attribute is seen but not covered by the compat dialect,
 * or when semantics are intentionally limited (e.g. signal event definition).
 */
public record CompatWarning(
        String elementId,
        String type,
        String reason
) {
}
