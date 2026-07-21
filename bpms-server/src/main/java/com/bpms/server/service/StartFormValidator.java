package com.bpms.server.service;

import com.bpms.core.definition.FormDataSpec;
import com.bpms.core.definition.FormFieldSpec;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.StartEventNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Start-event form validation + type coercion (plan 30, old ValidationService/BpmExecutionService parity).
 * Form is embedded in BPMN — no separate DB table.
 */
public final class StartFormValidator {

    private StartFormValidator() {}

    /**
     * Validates and coerces start-form input. Returns coerced values for {@code token_variable} seeding.
     * Skips when the process has no start form.
     */
    public static Map<String, Object> validateAndCoerce(ProcessDefinition model, Map<String, Object> input) {
        Optional<FormDataSpec> formOpt = startForm(model);
        if (formOpt.isEmpty() || formOpt.get().fields().isEmpty()) {
            return input == null ? Map.of() : Map.copyOf(input);
        }
        FormDataSpec form = formOpt.get();
        Map<String, Object> raw = input == null ? Map.of() : input;
        Map<String, Object> coerced = new LinkedHashMap<>();
        List<StartFormValidationException.FieldError> errors = new ArrayList<>();

        for (FormFieldSpec field : form.fields()) {
            Object value = raw.get(field.id());
            if (value == null && field.defaultValue() != null && !field.defaultValue().isBlank()) {
                value = field.defaultValue();
            }
            if (isBlank(value)) {
                if (isRequired(field, form.businessKeyVar())) {
                    errors.add(new StartFormValidationException.FieldError(field.id(), "required"));
                }
                continue;
            }
            try {
                coerced.put(field.id(), coerce(field.type(), value));
            } catch (IllegalArgumentException e) {
                errors.add(new StartFormValidationException.FieldError(field.id(), e.getMessage()));
            }
        }

        if (!errors.isEmpty()) {
            throw new StartFormValidationException(errors);
        }
        return Map.copyOf(coerced);
    }

    static boolean isRequired(FormFieldSpec field, String businessKeyVar) {
        if (field.validations().containsKey("required")) {
            return true;
        }
        if (businessKeyVar != null && businessKeyVar.equals(field.id())) {
            return true;
        }
        return false;
    }

    static Object coerce(String type, Object raw) {
        if (raw == null) {
            return null;
        }
        String t = type == null ? "string" : type.toLowerCase();
        return switch (t) {
            case "boolean", "bool" -> coerceBoolean(raw);
            case "long" -> coerceLong(raw);
            case "double" -> coerceDouble(raw);
            case "date" -> coerceDate(raw);
            default -> String.valueOf(raw);
        };
    }

    private static Boolean coerceBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s)) {
            return false;
        }
        throw new IllegalArgumentException("invalid boolean");
    }

    private static Long coerceLong(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid long");
        }
    }

    private static Double coerceDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid double");
        }
    }

    private static LocalDate coerceDate(Object raw) {
        try {
            return LocalDate.parse(String.valueOf(raw).trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid date");
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    static Optional<FormDataSpec> startForm(ProcessDefinition model) {
        return model.nodes().stream()
                .filter(StartEventNode.class::isInstance)
                .map(StartEventNode.class::cast)
                .findFirst()
                .flatMap(StartEventNode::formData);
    }
}
