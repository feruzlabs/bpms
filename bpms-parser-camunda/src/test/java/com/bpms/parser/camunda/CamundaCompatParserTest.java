package com.bpms.parser.camunda;

import com.bpms.core.definition.ConnectorImplementation;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ManualTaskNode;
import com.bpms.core.definition.ParseResult;
import com.bpms.core.definition.ServiceTaskNode;
import com.bpms.core.definition.StartEventNode;
import com.bpms.core.definition.UserTaskNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamundaCompatParserTest {

    private final CamundaCompatParser parser = new CamundaCompatParser();

    @Test
    void parsesConnectorIdAndInputs() throws Exception {
        ParseResult result = parser.parse(read("fixtures/simple-service-task.bpmn"));
        assertFalse(result.hasWarnings());
        ServiceTaskNode service = result.definition().nodes().stream()
                .filter(ServiceTaskNode.class::isInstance)
                .map(ServiceTaskNode.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(service.implementation() instanceof ConnectorImplementation);
        ConnectorImplementation impl = (ConnectorImplementation) service.implementation();
        assertEquals("DemoConnector", impl.binding().connectorId());
        assertEquals("pinfl", impl.binding().inputs().getFirst().name());
        assertEquals("123", impl.binding().inputs().getFirst().value());
    }

    @Test
    void mapsUnknownFormFieldTypeToCustomType() throws Exception {
        ParseResult result = parser.parse(read("fixtures/user-task-custom-form.bpmn"));
        assertFalse(result.hasWarnings());
        UserTaskNode userTask = result.definition().nodes().stream()
                .filter(UserTaskNode.class::isInstance)
                .map(UserTaskNode.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(userTask.formData().isPresent());
        var fields = userTask.formData().get().fields();
        assertEquals(2, fields.size());
        assertFalse(fields.get(0).customType());
        assertTrue(fields.get(1).customType());
        assertEquals("bpmn_collect_data", fields.get(1).type());
        assertEquals("5", fields.get(1).properties().get("max_size"));
        assertTrue(fields.get(1).properties().get("parameters").contains("parameter_name"));
        assertEquals("true", fields.get(1).validations().get("required"));
    }

    @Test
    void parsesHumanWorkflowExtensions() throws Exception {
        ParseResult result = parser.parse(read("fixtures/human-workflow.bpmn"));
        assertFalse(result.hasWarnings());

        StartEventNode start = result.definition().nodes().stream()
                .filter(StartEventNode.class::isInstance)
                .map(StartEventNode.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("starterUser", start.initiator());

        UserTaskNode ut = result.definition().nodes().stream()
                .filter(UserTaskNode.class::isInstance)
                .map(UserTaskNode.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("EMPLOYEE__$employee__empId", ut.assignee());
        assertEquals("$TASK_EXPIRED_DATE", ut.dueDate());
        assertEquals("7", ut.priority());
        assertEquals("hr,managers", ut.candidateGroups());
        assertEquals("approve_form", ut.formData().orElseThrow().formKey());
        assertEquals("task_level", ut.inputs().getFirst().name());
        assertEquals("PRIMARY", ut.inputs().getFirst().value());

        assertTrue(result.definition().nodes().stream().anyMatch(ManualTaskNode.class::isInstance));

        EndEventNode terminate = result.definition().nodes().stream()
                .filter(EndEventNode.class::isInstance)
                .map(EndEventNode.class::cast)
                .filter(EndEventNode::isTerminate)
                .findFirst()
                .orElseThrow();
        assertEquals("end_kill", terminate.id());
    }

    private static byte[] read(String classpath) throws Exception {
        try (InputStream in = CamundaCompatParserTest.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + classpath);
            }
            return in.readAllBytes();
        }
    }
}
