package com.bpms.engine;

import com.bpms.core.definition.BoundaryEventNode;
import com.bpms.core.definition.BusinessRuleTaskNode;
import com.bpms.core.definition.CallActivityNode;
import com.bpms.core.definition.ConnectorImplementation;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ExclusiveGatewayNode;
import com.bpms.core.definition.FlowNode;
import com.bpms.core.definition.InclusiveGatewayNode;
import com.bpms.core.definition.IntermediateCatchEventNode;
import com.bpms.core.definition.IntermediateThrowEventNode;
import com.bpms.core.definition.IoParameter;
import com.bpms.core.definition.ListenerImplKind;
import com.bpms.core.definition.ListenerKind;
import com.bpms.core.definition.ListenerSpec;
import com.bpms.core.definition.ManualTaskNode;
import com.bpms.core.definition.ParallelGatewayNode;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.ReceiveTaskNode;
import com.bpms.core.definition.ScriptTaskNode;
import com.bpms.core.definition.SendTaskNode;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.ServiceTaskNode;
import com.bpms.core.definition.StartEventNode;
import com.bpms.core.definition.SubProcessNode;
import com.bpms.core.definition.TaskNode;
import com.bpms.core.definition.UserTaskNode;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorResult;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.JobStatus;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;
import com.bpms.spi.expression.ExpressionEvaluator;
import com.bpms.spi.port.ClockPort;
import com.bpms.spi.port.ExecutionLogPort;
import com.bpms.spi.port.ExecutionLogPort.LogEntry;
import com.bpms.spi.port.IncidentPort;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.ListenerLogPort;
import com.bpms.spi.port.ListenerLogPort.ListenerLogEntry;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TerminationSignal;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.TokenStatePort;
import com.bpms.spi.port.VariableStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Framework-free token walker. Persistence stays behind SPI ports. */
public final class ExecutionEngine {

    private final ConnectorRegistry connectors;
    private final ExpressionEvaluator expressions;
    private final InstanceRepositoryPort instances;
    private final TokenRepositoryPort tokens;
    private final VariableStorePort variables;
    private final TaskRepositoryPort tasks;
    private final JobRepositoryPort jobs;
    private final JobQueuePort jobQueue;
    private final ClockPort clock;
    private final boolean asyncServiceTasks;
    private final ObjectMapper json;
    private final ExecutionLogPort execLog;
    private final TokenStatePort tokenState;
    private final ListenerLogPort listenerLog;
    private final TerminationSignal termination;
    private final IncidentPort incidents;
    private final int maxStepsPerRun;
    private final int maxNodeRevisitsPerRun;

    /** Absolute cap on transitions within one {@code run()} — guarantees any synchronous loop is cut (plan 27 §3b). */
    public static final int DEFAULT_MAX_STEPS_PER_RUN = 10_000;
    /** Per-run visit cap for a single node — trips loop detection sooner than the raw step budget. */
    public static final int DEFAULT_MAX_NODE_REVISITS_PER_RUN = 1_000;

    public ExecutionEngine(
            ConnectorRegistry connectors,
            ExpressionEvaluator expressions,
            InstanceRepositoryPort instances,
            TokenRepositoryPort tokens,
            VariableStorePort variables,
            TaskRepositoryPort tasks,
            JobRepositoryPort jobs,
            JobQueuePort jobQueue,
            ClockPort clock,
            boolean asyncServiceTasks,
            ObjectMapper json,
            ExecutionLogPort execLog,
            TokenStatePort tokenState,
            ListenerLogPort listenerLog,
            TerminationSignal termination,
            IncidentPort incidents,
            int maxStepsPerRun,
            int maxNodeRevisitsPerRun
    ) {
        this.connectors = connectors;
        this.expressions = expressions;
        this.instances = instances;
        this.tokens = tokens;
        this.variables = variables;
        this.tasks = tasks;
        this.jobs = jobs;
        this.jobQueue = jobQueue;
        this.clock = clock;
        this.asyncServiceTasks = asyncServiceTasks;
        this.json = json;
        this.execLog = Objects.requireNonNullElse(execLog, NoOpExecutionLogPort.INSTANCE);
        this.tokenState = Objects.requireNonNullElse(tokenState, NoOpTokenStatePort.INSTANCE);
        this.listenerLog = Objects.requireNonNullElse(listenerLog, NoOpListenerLogPort.INSTANCE);
        this.termination = Objects.requireNonNullElse(termination, TerminationSignal.NEVER);
        this.incidents = Objects.requireNonNullElse(incidents, IncidentPort.NOOP);
        this.maxStepsPerRun = maxStepsPerRun > 0 ? maxStepsPerRun : DEFAULT_MAX_STEPS_PER_RUN;
        this.maxNodeRevisitsPerRun = maxNodeRevisitsPerRun > 0 ? maxNodeRevisitsPerRun : DEFAULT_MAX_NODE_REVISITS_PER_RUN;
    }

    /** Backward-compatible overload: no cooperative stop / incidents, default budgets. */
    public ExecutionEngine(
            ConnectorRegistry connectors,
            ExpressionEvaluator expressions,
            InstanceRepositoryPort instances,
            TokenRepositoryPort tokens,
            VariableStorePort variables,
            TaskRepositoryPort tasks,
            JobRepositoryPort jobs,
            JobQueuePort jobQueue,
            ClockPort clock,
            boolean asyncServiceTasks,
            ObjectMapper json,
            ExecutionLogPort execLog,
            TokenStatePort tokenState,
            ListenerLogPort listenerLog
    ) {
        this(connectors, expressions, instances, tokens, variables, tasks, jobs, jobQueue,
                clock, asyncServiceTasks, json, execLog, tokenState, listenerLog,
                TerminationSignal.NEVER, IncidentPort.NOOP,
                DEFAULT_MAX_STEPS_PER_RUN, DEFAULT_MAX_NODE_REVISITS_PER_RUN);
    }

    /** Backward-compatible overload for callers that don't track token-state/listener history. */
    public ExecutionEngine(
            ConnectorRegistry connectors,
            ExpressionEvaluator expressions,
            InstanceRepositoryPort instances,
            TokenRepositoryPort tokens,
            VariableStorePort variables,
            TaskRepositoryPort tasks,
            JobRepositoryPort jobs,
            JobQueuePort jobQueue,
            ClockPort clock,
            boolean asyncServiceTasks,
            ObjectMapper json,
            ExecutionLogPort execLog
    ) {
        this(connectors, expressions, instances, tokens, variables, tasks, jobs, jobQueue,
                clock, asyncServiceTasks, json, execLog, NoOpTokenStatePort.INSTANCE, NoOpListenerLogPort.INSTANCE);
    }

    /** Backward-compatible overload for tests that omit the log port too. */
    public ExecutionEngine(
            ConnectorRegistry connectors,
            ExpressionEvaluator expressions,
            InstanceRepositoryPort instances,
            TokenRepositoryPort tokens,
            VariableStorePort variables,
            TaskRepositoryPort tasks,
            JobRepositoryPort jobs,
            JobQueuePort jobQueue,
            ClockPort clock,
            boolean asyncServiceTasks,
            ObjectMapper json
    ) {
        this(connectors, expressions, instances, tokens, variables, tasks, jobs, jobQueue,
                clock, asyncServiceTasks, json, NoOpExecutionLogPort.INSTANCE,
                NoOpTokenStatePort.INSTANCE, NoOpListenerLogPort.INSTANCE);
    }

    public void run(ProcessDefinition definition, TokenRecord token, String businessKey) {
        TokenRecord current = token;
        int steps = 0;
        Map<String, Integer> visitsPerNode = new HashMap<>();
        while (current.status() == TokenStatus.ACTIVE) {
            // (1) cooperative stop: an external SUSPEND/TERMINATE cancels this token and returns.
            if (termination.isHalted(current.instanceId())) {
                tokens.save(new TokenRecord(
                        current.id(), current.instanceId(), current.currentNodeId(), TokenStatus.CANCELED, null));
                return;
            }
            // (2) step budget: guarantees ANY synchronous infinite loop is cut even without a stop signal.
            if (++steps > maxStepsPerRun) {
                haltRunaway(current, "STEP_BUDGET_EXCEEDED",
                        "run() exceeded " + maxStepsPerRun + " transitions (possible infinite loop)");
                return;
            }
            // (3) per-node revisit cap: detects a tight loop earlier than the raw step budget.
            int visits = visitsPerNode.merge(current.currentNodeId(), 1, Integer::sum);
            if (visits > maxNodeRevisitsPerRun) {
                haltRunaway(current, "LOOP_DETECTED",
                        "node " + current.currentNodeId() + " visited " + visits + " times in one run() (loop)");
                return;
            }
            final TokenRecord at = current;
            final String nodeId = at.currentNodeId();
            FlowNode node = definition.node(nodeId)
                    .orElseThrow(() -> new IllegalStateException("Missing node " + nodeId));
            Map<String, Object> vars = variables.getAll(at.instanceId());

            Instant enteredAt = clock.now();
            String stateId = tokenState.enter(at.id(), at.instanceId(), nodeId, nodeTypeOf(node), enteredAt);

            try {
                fireListeners(node, stateId, "BEFORE", vars, at.instanceId(), nodeId);

                if (node instanceof EndEventNode) {
                    completeNodeState(stateId, node, vars, at.instanceId(), nodeId, enteredAt);
                    close(at);
                    return;
                }

                if (node instanceof UserTaskNode user) {
                    tokens.save(new TokenRecord(at.id(), at.instanceId(), at.currentNodeId(), TokenStatus.WAITING, null));
                    instances.save(withStatus(at.instanceId(), InstanceStatus.WAITING));
                    tasks.save(new UserTaskRecord(
                            UUID.randomUUID().toString(), at.instanceId(), at.id(),
                            user.id(), user.name(), false, clock.now(), null));
                    return;
                }

                if (node instanceof ParallelGatewayNode) {
                    List<SequenceFlow> incoming = definition.flows().stream()
                            .filter(f -> node.id().equals(f.targetRef()))
                            .toList();
                    List<SequenceFlow> outgoingParallel = definition.outgoing(node.id());
                    if (incoming.size() > 1) {
                        String joinKey = "_join_" + node.id();
                        int arrived = ((Number) vars.getOrDefault(joinKey, 0)).intValue() + 1;
                        variables.putAll(at.instanceId(), Map.of(joinKey, arrived));
                        tokens.save(new TokenRecord(at.id(), at.instanceId(), node.id(), TokenStatus.COMPLETED, null));
                        if (arrived < incoming.size()) {
                            completeNodeState(stateId, node, vars, at.instanceId(), nodeId, enteredAt);
                            return;
                        }
                        variables.putAll(at.instanceId(), Map.of(joinKey, 0));
                        if (outgoingParallel.isEmpty()) {
                            completeNodeState(stateId, node, vars, at.instanceId(), nodeId, enteredAt);
                            maybeCompleteInstance(at.instanceId());
                            return;
                        }
                        completeNodeState(stateId, node, vars, at.instanceId(), nodeId, enteredAt);
                        current = new TokenRecord(
                                UUID.randomUUID().toString(), at.instanceId(),
                                outgoingParallel.getFirst().targetRef(), TokenStatus.ACTIVE, null);
                        tokens.save(current);
                        continue;
                    }
                    if (outgoingParallel.size() > 1) {
                        for (int i = 1; i < outgoingParallel.size(); i++) {
                            tokens.save(new TokenRecord(
                                    UUID.randomUUID().toString(), at.instanceId(),
                                    outgoingParallel.get(i).targetRef(), TokenStatus.ACTIVE, null));
                        }
                        current = new TokenRecord(
                                at.id(), at.instanceId(),
                                outgoingParallel.getFirst().targetRef(), TokenStatus.ACTIVE, null);
                        tokens.save(current);
                        completeNodeState(stateId, node, vars, at.instanceId(), nodeId, enteredAt);
                        for (int i = 1; i < outgoingParallel.size(); i++) {
                            final String siblingNode = outgoingParallel.get(i).targetRef();
                            TokenRecord sibling = tokens.findByInstanceId(at.instanceId()).stream()
                                    .filter(t -> siblingNode.equals(t.currentNodeId()) && t.status() == TokenStatus.ACTIVE)
                                    .findFirst()
                                    .orElseThrow();
                            run(definition, sibling, businessKey);
                        }
                        continue;
                    }
                }

                if (node instanceof ServiceTaskNode service && service.implementation() instanceof ConnectorImplementation ci) {
                    Map<String, Object> inputs = new HashMap<>();
                    for (IoParameter p : ci.binding().inputs()) {
                        inputs.put(p.name(), expressions.evaluate(p.value(), vars));
                    }
                    if (asyncServiceTasks) {
                        enqueueServiceTask(at, businessKey, ci.binding().connectorId(), inputs);
                        return;
                    }
                    executeConnector(at, businessKey, ci.binding().connectorId(), inputs);
                    vars = variables.getAll(at.instanceId());
                }

                if (node instanceof ScriptTaskNode script && script.script() != null) {
                    Object result = expressions.evaluate(script.script(), vars);
                    if (script.resultVariable() != null && !script.resultVariable().isBlank()) {
                        variables.putAll(at.instanceId(), Map.of(script.resultVariable(), result));
                    }
                }

                final Map<String, Object> evalVars = vars;
                List<SequenceFlow> outgoing = new ArrayList<>(definition.outgoing(node.id()));
                if (node instanceof ExclusiveGatewayNode gateway) {
                    List<SequenceFlow> conditional = outgoing.stream()
                            .filter(f -> f.condition().isPresent()
                                    && expressions.evaluateLogic(f.condition().get().expression(), evalVars))
                            .toList();
                    boolean usedDefault;
                    if (!conditional.isEmpty()) {
                        outgoing = List.of(conditional.getFirst());
                        usedDefault = false;
                    } else {
                        List<SequenceFlow> outgoingFlows = outgoing;
                        SequenceFlow fallback = outgoing.stream()
                                .filter(f -> f.id().equals(gateway.defaultFlowId()))
                                .findFirst()
                                .or(() -> outgoingFlows.stream()
                                        .filter(f -> f.condition().isEmpty())
                                        .findFirst())
                                .orElse(null);
                        outgoing = fallback == null ? List.of() : List.of(fallback);
                        usedDefault = fallback != null;
                    }
                    if (!outgoing.isEmpty()) {
                        logGateway(at, gateway, outgoing.getFirst(), conditional, usedDefault);
                    }
                }

                if (node instanceof InclusiveGatewayNode gateway) {
                    List<SequenceFlow> conditional = outgoing.stream()
                            .filter(f -> f.condition().isPresent()
                                    && expressions.evaluateLogic(f.condition().get().expression(), evalVars))
                            .toList();
                    if (!conditional.isEmpty()) {
                        outgoing = conditional;
                    } else {
                        List<SequenceFlow> outgoingFlows = outgoing;
                        SequenceFlow fallback = outgoing.stream()
                                .filter(f -> f.id().equals(gateway.defaultFlowId()))
                                .findFirst()
                                .or(() -> outgoingFlows.stream()
                                        .filter(f -> f.condition().isEmpty())
                                        .findFirst())
                                .orElse(null);
                        outgoing = fallback == null ? List.of() : List.of(fallback);
                    }
                }

                if (outgoing.isEmpty()) {
                    completeNodeState(stateId, node, vars, at.instanceId(), nodeId, enteredAt);
                    close(at);
                    return;
                }
                completeNodeState(stateId, node, vars, at.instanceId(), nodeId, enteredAt);
                current = new TokenRecord(
                        at.id(), at.instanceId(),
                        outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, null);
                tokens.save(current);
            } catch (RuntimeException e) {
                tokenState.exit(stateId, "FAILED", clock.now(), durationMs(enteredAt), e.getMessage());
                throw e;
            }
        }
    }

    /** Called by JobQueue consumers after a serviceTask job finishes connector work. */
    public void continueAfterServiceTask(ProcessDefinition definition, TokenRecord waitingToken, String businessKey) {
        FlowNode node = definition.node(waitingToken.currentNodeId()).orElseThrow();
        Map<String, Object> vars = variables.getAll(waitingToken.instanceId());
        tokenState.activeStateId(waitingToken.id(), node.id()).ifPresent(stateId -> {
            try {
                fireListeners(node, stateId, "AFTER", vars, waitingToken.instanceId(), node.id());
                tokenState.exit(stateId, "COMPLETED", clock.now(), null, null);
            } catch (RuntimeException e) {
                tokenState.exit(stateId, "FAILED", clock.now(), null, e.getMessage());
                throw e;
            }
        });

        List<SequenceFlow> outgoing = definition.outgoing(node.id());
        if (outgoing.isEmpty()) {
            close(new TokenRecord(
                    waitingToken.id(), waitingToken.instanceId(), waitingToken.currentNodeId(),
                    TokenStatus.ACTIVE, null));
            return;
        }
        TokenRecord next = new TokenRecord(
                waitingToken.id(), waitingToken.instanceId(),
                outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, null);
        tokens.save(next);
        instances.save(withStatus(waitingToken.instanceId(), InstanceStatus.RUNNING));
        run(definition, next, businessKey);
    }

    public void executeConnector(TokenRecord token, String businessKey, String connectorId, Map<String, Object> inputs) {
        Instant t0 = clock.now();
        execLog.log(new LogEntry(
                token.instanceId(), token.id(), token.currentNodeId(), "serviceTask", connectorId,
                "CONNECTOR_START", null, null,
                Map.of("inputs", safeMap(inputs)), null, clock.now()));

        Map<String, Object> vars = variables.getAll(token.instanceId());
        ConnectorResult result;
        try {
            result = connectors.required(connectorId)
                    .execute(new ConnectorContext(businessKey, vars, inputs));
        } catch (Exception e) {
            markFailed(token, e.getMessage());
            execLog.log(new LogEntry(
                    token.instanceId(), token.id(), token.currentNodeId(), "serviceTask", connectorId,
                    "CONNECTOR_ERROR", "FAIL", e.getMessage(),
                    details(inputs, null), durationMs(t0), clock.now()));
            execLog.log(tokenFailed(token, e.getMessage()));
            execLog.log(instanceEnd(token.instanceId(), "FAIL", e.getMessage()));
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }

        if (!result.success()) {
            markFailed(token, result.errorMessage());
            execLog.log(new LogEntry(
                    token.instanceId(), token.id(), token.currentNodeId(), "serviceTask", connectorId,
                    "CONNECTOR_ERROR", "FAIL", result.errorMessage(),
                    details(inputs, result.outputs()), durationMs(t0), clock.now()));
            execLog.log(tokenFailed(token, result.errorMessage()));
            execLog.log(instanceEnd(token.instanceId(), "FAIL", result.errorMessage()));
            throw new IllegalStateException("Connector failed: " + connectorId + " — " + result.errorMessage());
        }

        variables.putAll(token.instanceId(), result.outputs());
        execLog.log(new LogEntry(
                token.instanceId(), token.id(), token.currentNodeId(), "serviceTask", connectorId,
                "CONNECTOR_END", "OK", null,
                details(inputs, result.outputs()), durationMs(t0), clock.now()));
    }

    private void enqueueServiceTask(TokenRecord token, String businessKey, String connectorId, Map<String, Object> inputs) {
        try {
            String payload = json.writeValueAsString(Map.of(
                    "connectorId", connectorId,
                    "businessKey", businessKey == null ? "" : businessKey,
                    "inputs", inputs
            ));
            JobRecord job = new JobRecord(
                    UUID.randomUUID().toString(), token.instanceId(), token.id(),
                    "SERVICE_TASK", payload, JobStatus.PENDING, 0, clock.now());
            jobs.save(job);
            tokens.save(new TokenRecord(token.id(), token.instanceId(), token.currentNodeId(), TokenStatus.WAITING_JOB, null));
            instances.save(withStatus(token.instanceId(), InstanceStatus.RUNNING));
            jobQueue.enqueue(job);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot enqueue service task", e);
        }
    }

    /** Fires AFTER-phase listeners then closes the node's execution_token_state row as COMPLETED. */
    private void completeNodeState(
            String stateId, FlowNode node, Map<String, Object> vars, String instanceId, String nodeId, Instant enteredAt
    ) {
        fireListeners(node, stateId, "AFTER", vars, instanceId, nodeId);
        tokenState.exit(stateId, "COMPLETED", clock.now(), durationMs(enteredAt), null);
    }

    /**
     * Fires every execution listener matching this phase, in order, logging each invocation.
     * A listener failure is not swallowed — it propagates so the caller marks the node FAILED
     * (plan 23: listeners are business-critical, e.g. audit writes).
     */
    private void fireListeners(
            FlowNode node, String stateId, String phase, Map<String, Object> vars, String instanceId, String nodeId
    ) {
        String camundaEvent = "BEFORE".equals(phase) ? "start" : "end";
        List<ListenerSpec> matching = node.listeners().stream()
                .filter(l -> l.kind() == ListenerKind.EXECUTION)
                .filter(l -> camundaEvent.equals(l.event()))
                .toList();
        int index = 0;
        for (ListenerSpec spec : matching) {
            Instant startedAt = clock.now();
            String listenerType = listenerTypeOf(spec.implKind());
            String listenerRef = listenerRefOf(spec);
            try {
                invokeListener(spec, vars);
                listenerLog.log(new ListenerLogEntry(
                        stateId, instanceId, nodeId, phase, index, listenerType, listenerRef,
                        "SUCCESS", startedAt, clock.now(), null));
            } catch (Exception e) {
                listenerLog.log(new ListenerLogEntry(
                        stateId, instanceId, nodeId, phase, index, listenerType, listenerRef,
                        "FAILED", startedAt, clock.now(), e.getMessage()));
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            }
            index++;
        }
    }

    private void invokeListener(ListenerSpec spec, Map<String, Object> vars) {
        switch (spec.implKind()) {
            case EXPRESSION -> expressions.evaluate(spec.expression(), vars);
            case SCRIPT -> expressions.evaluate(spec.script(), vars);
            case CLASS -> throw new UnsupportedOperationException(
                    "CLASS-kind execution listeners are not yet supported: " + spec.className());
        }
    }

    /** execution_listener_log.listener_type only allows CLASS/EXPRESSION/DELEGATE_EXPRESSION — SCRIPT maps to EXPRESSION. */
    private static String listenerTypeOf(ListenerImplKind kind) {
        return switch (kind) {
            case CLASS -> "CLASS";
            case EXPRESSION, SCRIPT -> "EXPRESSION";
        };
    }

    private static String listenerRefOf(ListenerSpec spec) {
        return switch (spec.implKind()) {
            case CLASS -> spec.className();
            case EXPRESSION -> spec.expression();
            case SCRIPT -> spec.script();
        };
    }

    private static String nodeTypeOf(FlowNode node) {
        return switch (node) {
            case StartEventNode n -> "startEvent";
            case EndEventNode n -> "endEvent";
            case ServiceTaskNode n -> "serviceTask";
            case UserTaskNode n -> "userTask";
            case ScriptTaskNode n -> "scriptTask";
            case ManualTaskNode n -> "manualTask";
            case SendTaskNode n -> "sendTask";
            case ReceiveTaskNode n -> "receiveTask";
            case BusinessRuleTaskNode n -> "businessRuleTask";
            case CallActivityNode n -> "callActivity";
            case ExclusiveGatewayNode n -> "exclusiveGateway";
            case ParallelGatewayNode n -> "parallelGateway";
            case InclusiveGatewayNode n -> "inclusiveGateway";
            case SubProcessNode n -> "subProcess";
            case BoundaryEventNode n -> "boundaryEvent";
            case IntermediateCatchEventNode n -> "intermediateCatchEvent";
            case IntermediateThrowEventNode n -> "intermediateThrowEvent";
            case TaskNode n -> "task";
        };
    }

    private void logGateway(
            TokenRecord token,
            ExclusiveGatewayNode gateway,
            SequenceFlow chosen,
            List<SequenceFlow> matched,
            boolean usedDefault
    ) {
        String label = usedDefault
                ? "→ " + chosen.id() + " (default)"
                : "→ " + chosen.id();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("chosenFlowId", chosen.id());
        details.put("targetRef", chosen.targetRef());
        details.put("default", usedDefault);
        if (!matched.isEmpty() && matched.getFirst().condition().isPresent()) {
            details.put("condition", matched.getFirst().condition().get().expression());
        } else if (gateway.defaultFlowId() != null) {
            details.put("defaultFlowId", gateway.defaultFlowId());
        }
        execLog.log(new LogEntry(
                token.instanceId(), token.id(), gateway.id(), "exclusiveGateway", null,
                "GATEWAY", "OK", label, details, null, clock.now()));
    }

    private void markFailed(TokenRecord token, String message) {
        tokens.save(new TokenRecord(token.id(), token.instanceId(), token.currentNodeId(), TokenStatus.FAILED, null));
        instances.save(withStatus(token.instanceId(), InstanceStatus.FAILED));
    }

    /**
     * Runaway guardrail trip (plan 27 §4): open an incident, audit-log it, and SUSPEND the instance so an
     * operator can inspect it. SUSPEND (not FAIL) keeps the door open for resume after a fix, and stops the
     * synchronous {@code run()} loop immediately.
     */
    private void haltRunaway(TokenRecord token, String type, String message) {
        incidents.raise(token.instanceId(), token.id(), null, type, "ERROR", message);
        execLog.log(new LogEntry(
                token.instanceId(), token.id(), token.currentNodeId(), null, null,
                type, "FAIL", message, null, null, clock.now()));
        instances.save(withStatus(token.instanceId(), InstanceStatus.SUSPENDED));
    }

    private LogEntry tokenFailed(TokenRecord token, String message) {
        return new LogEntry(
                token.instanceId(), token.id(), token.currentNodeId(), null, null,
                "TOKEN_FAILED", "FAIL", message, null, null, clock.now());
    }

    private LogEntry instanceEnd(String instanceId, String status, String message) {
        return new LogEntry(
                instanceId, null, null, null, null,
                "INSTANCE_END", status, message, null, null, clock.now());
    }

    private InstanceRecord withStatus(String id, InstanceStatus status) {
        InstanceRecord old = instances.findInstanceById(id).orElseThrow();
        return new InstanceRecord(
                old.id(), old.definitionId(), old.businessKey(), status, old.createdAt(),
                status == InstanceStatus.COMPLETED || status == InstanceStatus.FAILED ? clock.now() : old.endedAt());
    }

    private void close(TokenRecord token) {
        tokens.save(new TokenRecord(token.id(), token.instanceId(), token.currentNodeId(), TokenStatus.COMPLETED, null));
        maybeCompleteInstance(token.instanceId());
    }

    private void maybeCompleteInstance(String instanceId) {
        boolean allDone = tokens.findByInstanceId(instanceId).stream()
                .allMatch(t -> t.status() == TokenStatus.COMPLETED || t.status() == TokenStatus.FAILED);
        if (allDone) {
            instances.save(withStatus(instanceId, InstanceStatus.COMPLETED));
            execLog.log(instanceEnd(instanceId, "OK", null));
        }
    }

    private int durationMs(Instant t0) {
        return (int) Math.max(0, Duration.between(t0, clock.now()).toMillis());
    }

    private static Map<String, Object> details(Map<String, Object> inputs, Map<String, Object> outputs) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("inputs", safeMap(inputs));
        if (outputs != null) {
            d.put("outputs", safeMap(outputs));
        }
        return d;
    }

    /** Keep log details JSON-friendly (avoid recursive graphs). */
    private static Map<String, Object> safeMap(Map<String, Object> src) {
        if (src == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            Object v = e.getValue();
            if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) {
                out.put(e.getKey(), v);
            } else {
                out.put(e.getKey(), String.valueOf(v));
            }
        }
        return out;
    }
}
