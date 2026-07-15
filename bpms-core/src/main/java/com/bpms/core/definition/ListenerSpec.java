package com.bpms.core.definition;

public record ListenerSpec(
        ListenerKind kind,
        String event,
        ListenerImplKind implKind,
        String className,
        String expression,
        String script,
        String scriptFormat,
        String scriptResource
) {
}