package com.bpms.parser.camunda;

import java.util.Set;

final class TypedFormTypes {
    static final Set<String> TYPED = Set.of("string", "json", "boolean", "date", "long", "enum");

    private TypedFormTypes() {
    }

    static boolean isCustom(String type) {
        return type == null || !TYPED.contains(type);
    }
}