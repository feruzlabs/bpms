package com.bpms.server.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BpmnChecksumTest {

    @Test
    void identicalModelsShareChecksumDespiteWhitespace() {
        byte[] a = """
                <?xml version="1.0"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f" sourceRef="s" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.getBytes(StandardCharsets.UTF_8);
        byte[] b = """
                <?xml version="1.0"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"><bpmn:process id="p" isExecutable="true"><bpmn:startEvent id="s"/><bpmn:endEvent id="e"/><bpmn:sequenceFlow id="f" sourceRef="s" targetRef="e"/></bpmn:process></bpmn:definitions>
                """.getBytes(StandardCharsets.UTF_8);
        assertEquals(BpmnChecksum.sha256Canonical(a), BpmnChecksum.sha256Canonical(b));
    }

    @Test
    void changedContentChangesChecksum() {
        byte[] v1 = minimal("EndOk").getBytes(StandardCharsets.UTF_8);
        byte[] v2 = minimal("EndAlert").getBytes(StandardCharsets.UTF_8);
        assertNotEquals(BpmnChecksum.sha256Canonical(v1), BpmnChecksum.sha256Canonical(v2));
    }

    private static String minimal(String endId) {
        return """
                <?xml version="1.0"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:endEvent id="%s"/>
                    <bpmn:sequenceFlow id="f" sourceRef="s" targetRef="%s"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(endId, endId);
    }
}
