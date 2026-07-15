package com.bpms.engine;

import com.bpms.core.definition.ConnectorImplementation;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ExclusiveGatewayNode;
import com.bpms.core.definition.FlowNode;
import com.bpms.core.definition.IoParameter;
import com.bpms.core.definition.ParallelGatewayNode;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.ScriptTaskNode;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.ServiceTaskNode;
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
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.VariableStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    }

    public void run(ProcessDefinition definition, TokenRecord token, String businessKey) {
        TokenRecord current = token;
        while (current.status() == TokenStatus.ACTIVE) {
            final TokenRecord at = current;
            final String nodeId = at.currentNodeId();
            FlowNode node = definition.node(nodeId)
                    .orElseThrow(() -> new IllegalStateException("Missing node " + nodeId));
            Map<String, Object> vars = variables.getAll(at.instanceId());

            if (node instanceof EndEventNode) {
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
                        return;
                    }
                    variables.putAll(at.instanceId(), Map.of(joinKey, 0));
                    if (outgoingParallel.isEmpty()) {
                        maybeCompleteInstance(at.instanceId());
                        return;
                    }
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
                List<SequenceFlow> matched = outgoing.stream()
                        .filter(f -> f.condition().isPresent()
                                && expressions.evaluateLogic(f.condition().get().expression(), evalVars))
                        .toList();
                if (!matched.isEmpty()) {
                    outgoing = List.of(matched.getFirst());
                } else {
                    outgoing = outgoing.stream()
                            .filter(f -> f.id().equals(gateway.defaultFlowId()))
                            .toList();
                }
            }

            if (outgoing.isEmpty()) {
                close(at);
                return;
            }
            current = new TokenRecord(
                    at.id(), at.instanceId(),
                    outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, null);
            tokens.save(current);
        }
    }

    /** Called by JobQueue consumers after a serviceTask job finishes connector work. */
    public void continueAfterServiceTask(ProcessDefinition definition, TokenRecord waitingToken, String businessKey) {
        FlowNode node = definition.node(waitingToken.currentNodeId()).orElseThrow();
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
        Map<String, Object> vars = variables.getAll(token.instanceId());
        ConnectorResult result = connectors.required(connectorId)
                .execute(new ConnectorContext(businessKey, vars, inputs));
        if (!result.success()) {
            tokens.save(new TokenRecord(token.id(), token.instanceId(), token.currentNodeId(), TokenStatus.FAILED, null));
            instances.save(withStatus(token.instanceId(), InstanceStatus.FAILED));
            throw new IllegalStateException("Connector failed: " + connectorId + " — " + result.errorMessage());
        }
        variables.putAll(token.instanceId(), result.outputs());
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
        }
    }
}
