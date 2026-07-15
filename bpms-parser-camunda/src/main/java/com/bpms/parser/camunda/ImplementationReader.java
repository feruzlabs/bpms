package com.bpms.parser.camunda;

import com.bpms.core.definition.ClassImplementation;
import com.bpms.core.definition.ConnectorBinding;
import com.bpms.core.definition.ConnectorImplementation;
import com.bpms.core.definition.DelegateExpressionImplementation;
import com.bpms.core.definition.EmptyImplementation;
import com.bpms.core.definition.ExpressionImplementation;
import com.bpms.core.definition.ExternalTopicImplementation;
import com.bpms.core.definition.IoParameter;
import com.bpms.core.definition.TaskImplementation;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaConnector;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaEntry;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaInputOutput;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaInputParameter;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaList;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaMap;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaOutputParameter;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaValue;
import org.camunda.bpm.model.bpmn.instance.BaseElement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors {@code BpmHelper.getImplementation*} — connectorId from {@code camunda:connectorId} text.
 */
final class ImplementationReader {

    private ImplementationReader() {
    }

    static TaskImplementation read(ServiceTask task) {
        return readBase(task, task.getCamundaClass(), task.getCamundaExpression(), task.getCamundaResultVariable(),
                task.getCamundaDelegateExpression(), task.getCamundaTopic());
    }

    static TaskImplementation read(org.camunda.bpm.model.bpmn.instance.SendTask task) {
        return readBase(task, task.getCamundaClass(), task.getCamundaExpression(), task.getCamundaResultVariable(),
                task.getCamundaDelegateExpression(), task.getCamundaTopic());
    }

    static TaskImplementation read(org.camunda.bpm.model.bpmn.instance.BusinessRuleTask task) {
        return readBase(task, task.getCamundaClass(), task.getCamundaExpression(), task.getCamundaResultVariable(),
                task.getCamundaDelegateExpression(), task.getCamundaTopic());
    }

    private static TaskImplementation readBase(
            BaseElement element,
            String className,
            String expression,
            String resultVariable,
            String delegateExpression,
            String topic
    ) {
        if (element.getExtensionElements() != null) {
            int connectorCount = element.getExtensionElements().getElementsQuery()
                    .filterByType(CamundaConnector.class).count();
            if (connectorCount > 0) {
                CamundaConnector connector = element.getExtensionElements().getElementsQuery()
                        .filterByType(CamundaConnector.class).singleResult();
                String connectorId = connector.getCamundaConnectorId() != null
                        ? connector.getCamundaConnectorId().getTextContent()
                        : null;
                List<IoParameter> inputs = List.of();
                List<IoParameter> outputs = List.of();
                CamundaInputOutput io = connector.getCamundaInputOutput();
                if (io != null) {
                    inputs = readInputs(io.getCamundaInputParameters());
                    outputs = readOutputs(io.getCamundaOutputParameters());
                }
                return new ConnectorImplementation(new ConnectorBinding(connectorId, inputs, outputs));
            }
        }
        if (delegateExpression != null) {
            return new DelegateExpressionImplementation(delegateExpression);
        }
        if (topic != null) {
            return new ExternalTopicImplementation(topic);
        }
        if (className != null) {
            return new ClassImplementation(className);
        }
        if (expression != null) {
            return new ExpressionImplementation(expression, resultVariable);
        }
        return new EmptyImplementation();
    }

    private static List<IoParameter> readInputs(Collection<CamundaInputParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        List<IoParameter> result = new ArrayList<>();
        for (CamundaInputParameter parameter : parameters) {
            result.add(toIo(parameter.getCamundaName(), parameter));
        }
        return List.copyOf(result);
    }

    private static List<IoParameter> readOutputs(Collection<CamundaOutputParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        List<IoParameter> result = new ArrayList<>();
        for (CamundaOutputParameter parameter : parameters) {
            result.add(toIo(parameter.getCamundaName(), parameter));
        }
        return List.copyOf(result);
    }

    private static IoParameter toIo(String name, org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance element) {
        Collection<CamundaList> lists = element.getChildElementsByType(CamundaList.class);
        Collection<CamundaMap> maps = element.getChildElementsByType(CamundaMap.class);
        if (!lists.isEmpty()) {
            CamundaList list = lists.iterator().next();
            List<String> values = new ArrayList<>();
            for (org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance value : list.getValues()) {
                values.add(value.getTextContent());
            }
            return new IoParameter(name, null, List.copyOf(values), Map.of());
        }
        if (!maps.isEmpty()) {
            CamundaMap map = maps.iterator().next();
            Map<String, String> entries = new HashMap<>();
            for (CamundaEntry entry : map.getCamundaEntries()) {
                entries.put(entry.getCamundaKey(), entry.getTextContent());
            }
            return new IoParameter(name, null, List.of(), Map.copyOf(entries));
        }
        return IoParameter.scalar(name, element.getTextContent());
    }
}