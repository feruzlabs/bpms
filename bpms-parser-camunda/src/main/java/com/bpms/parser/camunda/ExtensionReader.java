package com.bpms.parser.camunda;

import com.bpms.core.definition.FormDataSpec;
import com.bpms.core.definition.FormEnumValue;
import com.bpms.core.definition.FormFieldSpec;
import com.bpms.core.definition.ListenerImplKind;
import com.bpms.core.definition.ListenerKind;
import com.bpms.core.definition.ListenerSpec;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaConstraint;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaExecutionListener;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaFormData;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaFormField;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaScript;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaTaskListener;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Form + listener extensions. Properties/validations are flat string maps (old BpmHelper).
 */
final class ExtensionReader {

    private ExtensionReader() {
    }

    static Optional<FormDataSpec> readFormData(BaseElement element) {
        if (element.getExtensionElements() == null) {
            return Optional.empty();
        }
        int count = element.getExtensionElements().getElementsQuery().filterByType(CamundaFormData.class).count();
        if (count == 0) {
            return Optional.empty();
        }
        CamundaFormData formData = element.getExtensionElements().getElementsQuery()
                .filterByType(CamundaFormData.class).singleResult();
        List<FormFieldSpec> fields = new ArrayList<>();
        for (CamundaFormField field : formData.getCamundaFormFields()) {
            fields.add(mapField(field));
        }
        String formKey = null;
        String businessKeyVar = null;
        if (element instanceof StartEvent startEvent) {
            formKey = startEvent.getCamundaFormKey();
            businessKeyVar = readBusinessKeyVarAttribute(formData);
            if (businessKeyVar == null || businessKeyVar.isBlank()) {
                businessKeyVar = inferTuneBusinessKeyVar(fields);
            }
        } else if (element instanceof UserTask userTask) {
            formKey = userTask.getCamundaFormKey();
        }
        return Optional.of(new FormDataSpec(formKey, businessKeyVar, List.copyOf(fields)));
    }

    /** Old FormService parity: {@code formDataModel.getAttributeValue("businessKey")}. */
    private static String readBusinessKeyVarAttribute(CamundaFormData formData) {
        try {
            return formData.getAttributeValue("businessKey");
        } catch (Exception e) {
            return null;
        }
    }

    /** TUNE corpus convention when formData has no businessKey attribute (4888/6004/7000). */
    static String inferTuneBusinessKeyVar(List<FormFieldSpec> fields) {
        for (FormFieldSpec field : fields) {
            String id = field.id();
            if (id != null && id.startsWith("request_id_") && id.endsWith("_start_form")) {
                return id;
            }
        }
        return null;
    }

    private static FormFieldSpec mapField(CamundaFormField field) {
        String type = field.getCamundaType();
        boolean custom = TypedFormTypes.isCustom(type);
        Map<String, String> properties = new HashMap<>();
        if (field.getCamundaProperties() != null && field.getCamundaProperties().getCamundaProperties() != null) {
            for (CamundaProperty property : field.getCamundaProperties().getCamundaProperties()) {
                properties.put(property.getCamundaId(), property.getCamundaValue());
            }
        }
        Map<String, String> validations = new HashMap<>();
        if (field.getCamundaValidation() != null && field.getCamundaValidation().getCamundaConstraints() != null) {
            for (CamundaConstraint constraint : field.getCamundaValidation().getCamundaConstraints()) {
                validations.put(constraint.getCamundaName(), constraint.getCamundaConfig());
            }
        }
        List<FormEnumValue> enumValues = new ArrayList<>();
        if (field.getCamundaValues() != null) {
            for (CamundaValue value : field.getCamundaValues()) {
                enumValues.add(new FormEnumValue(value.getCamundaId(), value.getCamundaName()));
            }
        }
        return new FormFieldSpec(
                field.getCamundaId(),
                field.getCamundaLabel(),
                type,
                field.getCamundaDefaultValue(),
                custom,
                Map.copyOf(properties),
                Map.copyOf(validations),
                List.copyOf(enumValues)
        );
    }

    static List<ListenerSpec> readListeners(BaseElement element) {
        if (element.getExtensionElements() == null) {
            return List.of();
        }
        List<ListenerSpec> listeners = new ArrayList<>();
        for (CamundaExecutionListener listener : element.getExtensionElements().getElementsQuery()
                .filterByType(CamundaExecutionListener.class).list()) {
            listeners.add(mapExecutionListener(listener));
        }
        for (CamundaTaskListener listener : element.getExtensionElements().getElementsQuery()
                .filterByType(CamundaTaskListener.class).list()) {
            listeners.add(mapTaskListener(listener));
        }
        return List.copyOf(listeners);
    }

    private static ListenerSpec mapExecutionListener(CamundaExecutionListener listener) {
        return mapListener(ListenerKind.EXECUTION, listener.getCamundaEvent(), listener.getCamundaClass(),
                listener.getCamundaExpression(), listener.getCamundaScript());
    }

    private static ListenerSpec mapTaskListener(CamundaTaskListener listener) {
        return mapListener(ListenerKind.TASK, listener.getCamundaEvent(), listener.getCamundaClass(),
                listener.getCamundaExpression(), listener.getCamundaScript());
    }

    private static ListenerSpec mapListener(
            ListenerKind kind,
            String event,
            String className,
            String expression,
            CamundaScript script
    ) {
        if (className != null) {
            return new ListenerSpec(kind, event, ListenerImplKind.CLASS, className, null, null, null, null);
        }
        if (expression != null) {
            return new ListenerSpec(kind, event, ListenerImplKind.EXPRESSION, null, expression, null, null, null);
        }
        if (script != null) {
            return new ListenerSpec(
                    kind,
                    event,
                    ListenerImplKind.SCRIPT,
                    null,
                    null,
                    script.getTextContent(),
                    script.getCamundaScriptFormat(),
                    script.getCamundaResource()
            );
        }
        return new ListenerSpec(kind, event, ListenerImplKind.EXPRESSION, null, null, null, null, null);
    }
}