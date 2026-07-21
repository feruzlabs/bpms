package com.bpms.server.service;

import com.bpms.core.definition.FormDataSpec;
import com.bpms.core.definition.FormFieldSpec;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.StartEventNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartFormValidatorTest {

    private static final String REQUEST_ID = "request_id_tune_credit_request_start_form";

    @Test
    void appliesBooleanDefaultWhenFieldOmitted() {
        ProcessDefinition model = tuneStartFormModel();
        Map<String, Object> coerced = StartFormValidator.validateAndCoerce(model, Map.of(
                REQUEST_ID, "REQ-1",
                "pinfl_tune_credit_request_start_form", "123",
                "passport_tune_credit_request_start_form", "AB123",
                "amount_tune_credit_request_start_form", "1000000"));

        assertFalse((Boolean) coerced.get("without_report_to_tune"));
    }

    @Test
    void coercesBooleanFromString() {
        ProcessDefinition model = tuneStartFormModel();
        Map<String, Object> coerced = StartFormValidator.validateAndCoerce(model, Map.of(
                REQUEST_ID, "REQ-1",
                "without_report_to_tune", "true"));

        assertTrue((Boolean) coerced.get("without_report_to_tune"));
    }

    @Test
    void missingBusinessKeyFieldThrows422() {
        ProcessDefinition model = tuneStartFormModel();
        StartFormValidationException ex = assertThrows(
                StartFormValidationException.class,
                () -> StartFormValidator.validateAndCoerce(model, Map.of(
                        "pinfl_tune_credit_request_start_form", "123")));
        assertTrue(ex.errors().stream().anyMatch(e -> REQUEST_ID.equals(e.field())));
    }

    @Test
    void noFormSkipsValidation() {
        ProcessDefinition model = new ProcessDefinition(
                "p", "p", "p", List.of(), List.of(), List.of(), List.of(), Map.of());
        Map<String, Object> out = StartFormValidator.validateAndCoerce(model, Map.of("x", 1));
        assertEquals(1, out.get("x"));
    }

    @Test
    void resolveBusinessKeyUsesFormFieldWhenBusinessKeyVarSet() {
        ProcessDefinition model = tuneStartFormModel();
        Map<String, Object> values = Map.of(REQUEST_ID, "REQ-FORM");
        assertEquals("REQ-FORM", ProcessEngineService.resolveBusinessKey(model, values, "REQ-HTTP"));
    }

    @Test
    void resolveBusinessKeyFallsBackToRequestWhenNoBusinessKeyVar() {
        ProcessDefinition model = new ProcessDefinition(
                "p", "p", "p",
                List.of(new StartEventNode("start", null, Optional.empty(),
                        Optional.of(new FormDataSpec("form", null, List.of(
                                new FormFieldSpec("other", null, "string", null, false, Map.of(), Map.of(), List.of())
                        ))),
                        Optional.empty(), List.of())),
                List.of(), List.of(), List.of(), Map.of());
        assertEquals("REQ-HTTP", ProcessEngineService.resolveBusinessKey(model, Map.of(), "REQ-HTTP"));
    }

    private static ProcessDefinition tuneStartFormModel() {
        return new ProcessDefinition(
                "TUNE_CREDIT_REQUEST", "Tune", "TUNE_CREDIT_REQUEST",
                List.of(new StartEventNode("start", null, Optional.empty(),
                        Optional.of(new FormDataSpec(
                                "tune_credit_request_start_form",
                                REQUEST_ID,
                                List.of(
                                        field(REQUEST_ID, "string"),
                                        field("pinfl_tune_credit_request_start_form", "string"),
                                        field("passport_tune_credit_request_start_form", "string"),
                                        field("without_report_to_tune", "boolean", "false"),
                                        field("amount_tune_credit_request_start_form", "string")
                                ))),
                        Optional.empty(), List.of())),
                List.of(), List.of(), List.of(), Map.of());
    }

    private static FormFieldSpec field(String id, String type) {
        return field(id, type, null);
    }

    private static FormFieldSpec field(String id, String type, String defaultValue) {
        return new FormFieldSpec(id, null, type, defaultValue, false, Map.of(), Map.of(), List.of());
    }
}
