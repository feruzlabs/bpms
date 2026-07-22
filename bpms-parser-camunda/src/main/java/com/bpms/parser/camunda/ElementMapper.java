package com.bpms.parser.camunda;

import com.bpms.core.definition.BoundaryEventNode;
import com.bpms.core.definition.BusinessRuleTaskNode;
import com.bpms.core.definition.CallActivityNode;
import com.bpms.core.definition.ConditionExpr;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ExclusiveGatewayNode;
import com.bpms.core.definition.InclusiveGatewayNode;
import com.bpms.core.definition.IntermediateCatchEventNode;
import com.bpms.core.definition.IntermediateThrowEventNode;
import com.bpms.core.definition.Lane;
import com.bpms.core.definition.ManualTaskNode;
import com.bpms.core.definition.MessageEventDef;
import com.bpms.core.definition.MultiInstanceSpec;
import com.bpms.core.definition.ParallelGatewayNode;
import com.bpms.core.definition.ReceiveTaskNode;
import com.bpms.core.definition.ScriptTaskNode;
import com.bpms.core.definition.SendTaskNode;
import com.bpms.core.definition.ServiceTaskNode;
import com.bpms.core.definition.StartEventNode;
import com.bpms.core.definition.SubProcessNode;
import com.bpms.core.definition.TaskNode;
import com.bpms.core.definition.TerminateEventDef;
import com.bpms.core.definition.UnsupportedEventDef;
import com.bpms.core.definition.UserTaskNode;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent;
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.EndEvent;
import org.camunda.bpm.model.bpmn.instance.Event;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.camunda.bpm.model.bpmn.instance.IntermediateThrowEvent;
import org.camunda.bpm.model.bpmn.instance.ManualTask;
import org.camunda.bpm.model.bpmn.instance.MessageEventDefinition;
import org.camunda.bpm.model.bpmn.instance.ParallelGateway;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.ReceiveTask;
import org.camunda.bpm.model.bpmn.instance.ScriptTask;
import org.camunda.bpm.model.bpmn.instance.SendTask;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.SubProcess;
import org.camunda.bpm.model.bpmn.instance.Task;
import org.camunda.bpm.model.bpmn.instance.TerminateEventDefinition;
import org.camunda.bpm.model.bpmn.instance.TimerEventDefinition;
import org.camunda.bpm.model.bpmn.instance.UserTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Mirrors {@code BpmnService.importFlowElement} for Process and SubProcess. */
final class ElementMapper {

    record MappedProcess(
            List<com.bpms.core.definition.FlowNode> nodes,
            List<com.bpms.core.definition.SequenceFlow> flows,
            List<com.bpms.core.definition.LaneSet> laneSets
    ) {
    }

    MappedProcess mapProcess(Process process, ParseContext ctx) {
        List<com.bpms.core.definition.FlowNode> nodes = new ArrayList<>();
        List<com.bpms.core.definition.SequenceFlow> flows = new ArrayList<>();
        List<com.bpms.core.definition.LaneSet> laneSets = new ArrayList<>();

        for (FlowElement element : process.getFlowElements()) {
            mapFlowElement(element, nodes, flows, laneSets, ctx);
        }
        for (org.camunda.bpm.model.bpmn.instance.LaneSet laneSet : process.getLaneSets()) {
            laneSets.add(mapLaneSet(laneSet));
        }
        return new MappedProcess(List.copyOf(nodes), List.copyOf(flows), List.copyOf(laneSets));
    }

    private void mapFlowElement(
            FlowElement element,
            List<com.bpms.core.definition.FlowNode> nodes,
            List<com.bpms.core.definition.SequenceFlow> flows,
            List<com.bpms.core.definition.LaneSet> laneSets,
            ParseContext ctx
    ) {
        String type = element.getElementType().getTypeName();
        switch (type) {
            case "startEvent" -> nodes.add(mapStartEvent((StartEvent) element, ctx));
            case "endEvent" -> nodes.add(mapEndEvent((EndEvent) element, ctx));
            case "task" -> nodes.add(mapNoneTask((Task) element));
            case "serviceTask" -> nodes.add(mapServiceTask((ServiceTask) element));
            case "userTask" -> nodes.add(mapUserTask((UserTask) element));
            case "scriptTask" -> nodes.add(mapScriptTask((ScriptTask) element));
            case "manualTask" -> nodes.add(mapManualTask((ManualTask) element));
            case "sendTask" -> nodes.add(mapSendTask((SendTask) element));
            case "receiveTask" -> nodes.add(mapReceiveTask((ReceiveTask) element));
            case "businessRuleTask" -> nodes.add(mapBusinessRuleTask((BusinessRuleTask) element));
            case "callActivity" -> nodes.add(mapCallActivity((CallActivity) element));
            case "exclusiveGateway" -> nodes.add(mapExclusiveGateway((ExclusiveGateway) element));
            case "parallelGateway" -> nodes.add(mapParallelGateway((ParallelGateway) element));
            case "inclusiveGateway" -> nodes.add(mapInclusiveGateway((InclusiveGateway) element));
            case "subProcess" -> nodes.add(mapSubProcess((SubProcess) element, ctx));
            case "boundaryEvent" -> nodes.add(mapBoundaryEvent((BoundaryEvent) element, ctx));
            case "intermediateCatchEvent" -> nodes.add(mapIntermediateCatch((IntermediateCatchEvent) element, ctx));
            case "intermediateThrowEvent" -> nodes.add(mapIntermediateThrow((IntermediateThrowEvent) element, ctx));
            case "sequenceFlow" -> flows.add(mapSequenceFlow((org.camunda.bpm.model.bpmn.instance.SequenceFlow) element));
            case "laneSet" -> laneSets.add(mapLaneSet((org.camunda.bpm.model.bpmn.instance.LaneSet) element));
            default -> ctx.warn(element.getId(), type, "Element type not covered by CamundaCompatParser");
        }
    }

    private StartEventNode mapStartEvent(StartEvent event, ParseContext ctx) {
        return new StartEventNode(
                event.getId(), event.getName(), mapEventDefinitions(event, ctx),
                ExtensionReader.readFormData(event), event.getCamundaInitiator(),
                Optional.empty(), ExtensionReader.readListeners(event));
    }

    private EndEventNode mapEndEvent(EndEvent event, ParseContext ctx) {
        return new EndEventNode(
                event.getId(), event.getName(), mapEventDefinitions(event, ctx),
                Optional.empty(), ExtensionReader.readListeners(event));
    }

    private TaskNode mapNoneTask(Task task) {
        return new TaskNode(task.getId(), task.getName(), multiInstance(task), ExtensionReader.readListeners(task));
    }

    private ServiceTaskNode mapServiceTask(ServiceTask task) {
        return new ServiceTaskNode(
                task.getId(), task.getName(), ImplementationReader.read(task),
                multiInstance(task), ExtensionReader.readListeners(task));
    }

    private UserTaskNode mapUserTask(UserTask task) {
        return new UserTaskNode(
                task.getId(), task.getName(), ExtensionReader.readFormData(task),
                task.getCamundaAssignee(),
                task.getCamundaCandidateGroups(),
                task.getCamundaCandidateUsers(),
                task.getCamundaDueDate(),
                task.getCamundaPriority(),
                ImplementationReader.readElementInputs(task),
                ImplementationReader.readElementOutputs(task),
                multiInstance(task), ExtensionReader.readListeners(task));
    }

    private ScriptTaskNode mapScriptTask(ScriptTask task) {
        return new ScriptTaskNode(
                task.getId(), task.getName(), task.getScriptFormat(),
                task.getScript() != null ? task.getScript().getTextContent() : null,
                task.getCamundaResource(), task.getCamundaResultVariable(),
                multiInstance(task), ExtensionReader.readListeners(task));
    }

    private ManualTaskNode mapManualTask(ManualTask task) {
        return new ManualTaskNode(
                task.getId(), task.getName(),
                ImplementationReader.readElementInputs(task),
                ImplementationReader.readElementOutputs(task),
                multiInstance(task), ExtensionReader.readListeners(task));
    }

    private SendTaskNode mapSendTask(SendTask task) {
        return new SendTaskNode(
                task.getId(), task.getName(), ImplementationReader.read(task),
                multiInstance(task), ExtensionReader.readListeners(task));
    }

    private ReceiveTaskNode mapReceiveTask(ReceiveTask task) {
        String messageRef = task.getMessage() != null ? task.getMessage().getId() : null;
        return new ReceiveTaskNode(
                task.getId(), task.getName(), messageRef, multiInstance(task), ExtensionReader.readListeners(task));
    }

    private BusinessRuleTaskNode mapBusinessRuleTask(BusinessRuleTask task) {
        return new BusinessRuleTaskNode(
                task.getId(), task.getName(), ImplementationReader.read(task),
                multiInstance(task), ExtensionReader.readListeners(task));
    }

    private CallActivityNode mapCallActivity(CallActivity task) {
        return new CallActivityNode(
                task.getId(), task.getName(), task.getCalledElement(),
                multiInstance(task), ExtensionReader.readListeners(task));
    }

    private ExclusiveGatewayNode mapExclusiveGateway(ExclusiveGateway gateway) {
        String defaultId = gateway.getDefault() != null ? gateway.getDefault().getId() : null;
        return new ExclusiveGatewayNode(
                gateway.getId(), gateway.getName(), defaultId, Optional.empty(), ExtensionReader.readListeners(gateway));
    }

    private ParallelGatewayNode mapParallelGateway(ParallelGateway gateway) {
        return new ParallelGatewayNode(
                gateway.getId(), gateway.getName(), Optional.empty(), ExtensionReader.readListeners(gateway));
    }

    private InclusiveGatewayNode mapInclusiveGateway(InclusiveGateway gateway) {
        String defaultId = gateway.getDefault() != null ? gateway.getDefault().getId() : null;
        return new InclusiveGatewayNode(
                gateway.getId(), gateway.getName(), defaultId, Optional.empty(), ExtensionReader.readListeners(gateway));
    }

    private SubProcessNode mapSubProcess(SubProcess subProcess, ParseContext ctx) {
        List<com.bpms.core.definition.FlowNode> childNodes = new ArrayList<>();
        List<com.bpms.core.definition.SequenceFlow> childFlows = new ArrayList<>();
        List<com.bpms.core.definition.LaneSet> childLaneSets = new ArrayList<>();
        for (FlowElement element : subProcess.getFlowElements()) {
            mapFlowElement(element, childNodes, childFlows, childLaneSets, ctx);
        }
        for (org.camunda.bpm.model.bpmn.instance.LaneSet laneSet : subProcess.getLaneSets()) {
            childLaneSets.add(mapLaneSet(laneSet));
        }
        return new SubProcessNode(
                subProcess.getId(), subProcess.getName(),
                List.copyOf(childNodes), List.copyOf(childFlows), List.copyOf(childLaneSets),
                multiInstance(subProcess), ExtensionReader.readListeners(subProcess));
    }

    private BoundaryEventNode mapBoundaryEvent(BoundaryEvent event, ParseContext ctx) {
        String attached = event.getAttachedTo() != null ? event.getAttachedTo().getId() : null;
        return new BoundaryEventNode(
                event.getId(), event.getName(), attached, event.cancelActivity(),
                mapEventDefinitions(event, ctx), Optional.empty(), ExtensionReader.readListeners(event));
    }

    private IntermediateCatchEventNode mapIntermediateCatch(IntermediateCatchEvent event, ParseContext ctx) {
        return new IntermediateCatchEventNode(
                event.getId(), event.getName(), mapEventDefinitions(event, ctx),
                Optional.empty(), ExtensionReader.readListeners(event));
    }

    private IntermediateThrowEventNode mapIntermediateThrow(IntermediateThrowEvent event, ParseContext ctx) {
        return new IntermediateThrowEventNode(
                event.getId(), event.getName(), mapEventDefinitions(event, ctx),
                Optional.empty(), ExtensionReader.readListeners(event));
    }

    private com.bpms.core.definition.SequenceFlow mapSequenceFlow(org.camunda.bpm.model.bpmn.instance.SequenceFlow flow) {
        Optional<ConditionExpr> condition = Optional.empty();
        if (flow.getConditionExpression() != null) {
            String text = flow.getConditionExpression().getTextContent();
            if (text != null && !text.isBlank()) {
                condition = Optional.of(new ConditionExpr(text));
            }
        }
        return new com.bpms.core.definition.SequenceFlow(
                flow.getId(),
                flow.getName(),
                flow.getSource() != null ? flow.getSource().getId() : null,
                flow.getTarget() != null ? flow.getTarget().getId() : null,
                condition
        );
    }

    private com.bpms.core.definition.LaneSet mapLaneSet(org.camunda.bpm.model.bpmn.instance.LaneSet laneSet) {
        List<Lane> lanes = new ArrayList<>();
        for (org.camunda.bpm.model.bpmn.instance.Lane lane : laneSet.getLanes()) {
            lanes.add(mapLane(lane));
        }
        return new com.bpms.core.definition.LaneSet(laneSet.getId(), List.copyOf(lanes));
    }

    private Lane mapLane(org.camunda.bpm.model.bpmn.instance.Lane lane) {
        List<String> refs = new ArrayList<>();
        for (org.camunda.bpm.model.bpmn.instance.FlowNode node : lane.getFlowNodeRefs()) {
            refs.add(node.getId());
        }
        List<Lane> children = new ArrayList<>();
        org.camunda.bpm.model.bpmn.instance.LaneSet childSet = lane.getChildLaneSet();
        if (childSet != null) {
            for (org.camunda.bpm.model.bpmn.instance.Lane child : childSet.getLanes()) {
                children.add(mapLane(child));
            }
        }
        return new Lane(lane.getId(), lane.getName(), List.copyOf(refs), List.copyOf(children));
    }

    private Optional<MultiInstanceSpec> multiInstance(Activity activity) {
        return MultiInstanceReader.read(activity);
    }

    private Optional<com.bpms.core.definition.EventDefinition> mapEventDefinitions(Event event, ParseContext ctx) {
        // Same approach as old EventService: child element types, not Event.getEventDefinitions()
        Collection<MessageEventDefinition> messages = event.getChildElementsByType(MessageEventDefinition.class);
        if (!messages.isEmpty()) {
            MessageEventDefinition messageDef = messages.iterator().next();
            String ref = messageDef.getMessage() != null ? messageDef.getMessage().getId() : null;
            String name = messageDef.getMessage() != null ? messageDef.getMessage().getName() : null;
            return Optional.of(new MessageEventDef(ref, name));
        }
        Collection<TimerEventDefinition> timers = event.getChildElementsByType(TimerEventDefinition.class);
        if (!timers.isEmpty()) {
            return Optional.of(TimerReader.read(timers.iterator().next()));
        }
        Collection<TerminateEventDefinition> terminates = event.getChildElementsByType(TerminateEventDefinition.class);
        if (!terminates.isEmpty()) {
            return Optional.of(new TerminateEventDef());
        }
        Collection<org.camunda.bpm.model.bpmn.instance.EventDefinition> other =
                event.getChildElementsByType(org.camunda.bpm.model.bpmn.instance.EventDefinition.class);
        if (!other.isEmpty()) {
            org.camunda.bpm.model.bpmn.instance.EventDefinition first = other.iterator().next();
            String defType = first.getElementType().getTypeName();
            ctx.warn(event.getId(), defType,
                    "Event definition not given semantics by old engine (only message/timer/terminate); recorded as unsupported");
            return Optional.of(new UnsupportedEventDef(defType));
        }
        return Optional.empty();
    }
}
