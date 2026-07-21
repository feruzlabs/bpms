package com.bpms.parser.camunda;

import com.bpms.core.definition.FormDataSpec;
import com.bpms.core.definition.StartEventNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 30: start formKey + businessKeyVar extracted from real BPMN. */
class StartFormParseTest {

    private final CamundaCompatParser parser = new CamundaCompatParser();

    @Test
    void parses4888StartFormMetadata() throws Exception {
        Path bpmn = locate("TUNE_CREDIT_REQUEST_4888.bpmn");
        Assumptions.assumeTrue(Files.isRegularFile(bpmn));

        var model = parser.parse(Files.readAllBytes(bpmn)).definition();
        StartEventNode start = model.nodes().stream()
                .filter(StartEventNode.class::isInstance)
                .map(StartEventNode.class::cast)
                .findFirst()
                .orElseThrow();
        FormDataSpec form = start.formData().orElseThrow();

        assertEquals("tune_credit_request_start_form", form.formKey());
        assertEquals("request_id_tune_credit_request_start_form", form.businessKeyVar());
        assertFalse(form.fields().isEmpty());
        assertTrue(form.fields().stream().anyMatch(f -> "without_report_to_tune".equals(f.id())));
    }

    private static Path locate(String fileName) {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = {
                cwd.resolve("compat-corpus/credit-conveyor/" + fileName),
                cwd.resolve("../compat-corpus/credit-conveyor/" + fileName),
                cwd.getParent().resolve("compat-corpus/credit-conveyor/" + fileName)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        return candidates[0].normalize();
    }
}
