package com.bpms.parser.camunda;

import com.bpms.core.compat.CompatWarning;
import com.bpms.core.definition.ParseResult;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.spi.parse.ProcessDefinitionParser;
import com.bpms.spi.parse.SourceFormat;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Collaboration;
import org.camunda.bpm.model.bpmn.instance.Message;
import org.camunda.bpm.model.bpmn.instance.Participant;
import org.camunda.bpm.model.bpmn.instance.Process;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reproduces old-engine Camunda reading rules (plan 14 §1). Uncovered elements → {@link CompatWarning}.
 */
public final class CamundaCompatParser implements ProcessDefinitionParser {

    private final ElementMapper elementMapper = new ElementMapper();

    @Override
    public boolean supports(SourceFormat format) {
        return format == SourceFormat.CAMUNDA_BPMN_XML;
    }

    @Override
    public ParseResult parse(byte[] source) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("BPMN source is empty");
        }
        BpmnModelInstance model = Bpmn.readModelFromStream(new ByteArrayInputStream(source));
        List<CompatWarning> warnings = new ArrayList<>();

        Collection<Process> processes = model.getModelElementsByType(Process.class);
        if (processes.isEmpty()) {
            warnings.add(new CompatWarning(null, "definitions", "No bpmn:process found"));
            return new ParseResult(
                    new ProcessDefinition(null, null, null, List.of(), List.of(), List.of(), List.of(), Map.of()),
                    List.copyOf(warnings));
        }

        Process process = processes.iterator().next();
        if (processes.size() > 1) {
            warnings.add(new CompatWarning(process.getId(), "process",
                    "Multiple processes present; parsing first only (" + processes.size() + " found)"));
        }

        ParseContext ctx = new ParseContext(warnings);
        ElementMapper.MappedProcess mapped = elementMapper.mapProcess(process, ctx);

        for (Collaboration collaboration : model.getModelElementsByType(Collaboration.class)) {
            ctx.warn(collaboration.getId(), "collaboration",
                    "Collaboration/pool imported as metadata only (message-flow correlation deferred)");
            for (Participant participant : collaboration.getParticipants()) {
                ctx.putMeta("participant:" + participant.getId(), participant.getName());
            }
        }

        List<com.bpms.core.definition.MessageDef> messages = new ArrayList<>();
        for (Message message : model.getModelElementsByType(Message.class)) {
            messages.add(new com.bpms.core.definition.MessageDef(message.getId(), message.getName()));
        }

        Map<String, Object> metadata = new HashMap<>(ctx.metadata());
        ProcessDefinition definition = new ProcessDefinition(
                process.getId(),
                process.getName(),
                process.getId(),
                mapped.nodes(),
                mapped.flows(),
                List.copyOf(messages),
                mapped.laneSets(),
                Map.copyOf(metadata)
        );
        return new ParseResult(definition, List.copyOf(warnings));
    }
}