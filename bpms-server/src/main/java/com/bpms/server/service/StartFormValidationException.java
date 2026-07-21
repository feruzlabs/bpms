package com.bpms.server.service;

import java.util.List;

/** HTTP 422 — start-form field validation failed (plan 30 §6). */
public final class StartFormValidationException extends RuntimeException {

    private final List<FieldError> errors;

    public StartFormValidationException(List<FieldError> errors) {
        super("Start form validation failed");
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }

    public record FieldError(String field, String message) {}
}
