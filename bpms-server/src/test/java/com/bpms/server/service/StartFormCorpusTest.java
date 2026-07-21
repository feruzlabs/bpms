package com.bpms.server.service;

import com.bpms.core.definition.FormDataSpec;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.StartEventNode;
import com.bpms.parser.camunda.CamundaCompatParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 30: real corpus start-forms parse + validate (4888/6004/7000). */
class StartFormCorpusTest {

    private static final String REQUEST_ID = "request_id_tune_credit_request_start_form";
    private final CamundaCompatParser parser = new CamundaCompatParser();

    @ParameterizedTest
    @ValueSource(strings = {
            "TUNE_CREDIT_REQUEST_4888.bpmn",
            "TUNE_CREDIT_REQUEST_6004.bpmn",
            "TUNE_CREDIT_REQUEST_7000.bpmn"
    })
    void corpusStartFormParsesAndValidates(String fileName) throws Exception {
        ProcessDefinition model = parse(fileName);
        FormDataSpec form = startForm(model).orElseThrow();
        assertEquals("tune_credit_request_start_form", form.formKey());
        assertEquals(REQUEST_ID, form.businessKeyVar());

        Map<String, Object> input = validTuneInput();
        Map<String, Object> coerced = StartFormValidator.validateAndCoerce(model, input);
        assertEquals("REQ-CORPUS-99", coerced.get(REQUEST_ID));
        assertFalse((Boolean) coerced.get("without_report_to_tune"));

        String bk = ProcessEngineService.resolveBusinessKey(model, coerced, "ignored-request-bk");
        assertEquals("REQ-CORPUS-99", bk);
    }

    @Test
    void corpusMissingRequestIdFailsValidation() throws Exception {
        ProcessDefinition model = parse("TUNE_CREDIT_REQUEST_4888.bpmn");
        Map<String, Object> bad = new HashMap<>(validTuneInput());
        bad.remove(REQUEST_ID);
        assertThrows422(model, bad);
    }

    private static void assertThrows422(ProcessDefinition model, Map<String, Object> input) {
        org.junit.jupiter.api.Assertions.assertThrows(
                StartFormValidationException.class,
                () -> StartFormValidator.validateAndCoerce(model, input));
    }

    private static Map<String, Object> validTuneInput() {
        return Map.of(
                REQUEST_ID, "REQ-CORPUS-99",
                "pinfl_tune_credit_request_start_form", "12345678901234",
                "passport_tune_credit_request_start_form", "AB1234567",
                "product_code_abs_credit_request_start_form", "PROD1",
                "amount_tune_credit_request_start_form", "5000000",
                "percent_tune_credit_request_start_form", "24",
                "term_tune_credit_request_start_form", "12",
                "grace_tune_credit_request_start_form", "0");
    }

    private ProcessDefinition parse(String fileName) throws Exception {
        Path bpmn = locate(fileName);
        Assumptions.assumeTrue(Files.isRegularFile(bpmn), "Missing corpus: " + bpmn);
        return parser.parse(Files.readAllBytes(bpmn)).definition();
    }

    private static Optional<FormDataSpec> startForm(ProcessDefinition model) {
        return model.nodes().stream()
                .filter(StartEventNode.class::isInstance)
                .map(StartEventNode.class::cast)
                .findFirst()
                .flatMap(StartEventNode::formData);
    }

    private static Path locate(String fileName) {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = {
                cwd.resolve("compat-corpus/credit-conveyor/" + fileName),
                cwd.resolve("../compat-corpus/credit-conveyor/" + fileName),
                cwd.resolve("../../compat-corpus/credit-conveyor/" + fileName),
                cwd.getParent().resolve("compat-corpus/credit-conveyor/" + fileName)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        return candidates[1].normalize();
    }
}
