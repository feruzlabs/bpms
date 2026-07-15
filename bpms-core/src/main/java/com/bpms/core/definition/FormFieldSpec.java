package com.bpms.core.definition;

import java.util.List;
import java.util.Map;

public record FormFieldSpec(
        String id,
        String label,
        String type,
        String defaultValue,
        boolean customType,
        Map<String, String> properties,
        Map<String, String> validations,
        List<FormEnumValue> enumValues
) {
}