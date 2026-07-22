package com.bpms.engine.behavior;

import com.bpms.core.definition.ExclusiveGatewayNode;
import com.bpms.core.definition.FlowNode;
import com.bpms.core.definition.IoParameter;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.engine.ConnectorRegistry;
import com.bpms.engine.ExecutionEngine;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.expression.ExpressionEvaluator;
import com.bpms.spi.port.ClockPort;
import com.bpms.spi.port.DefinitionLookupPort;
import com.bpms.spi.port.EventSubscriptionPort;
import com.bpms.spi.port.ExecutionLogPort;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.SpawnGuardPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.TokenStatePort;
import com.bpms.spi.port.VariableStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Per-node-visit facade over {@link ExecutionEngine}'s ports and helper methods, handed to
 * {@link NodeBehavior}s so node-specific logic never needs to know about the engine's private wiring
 * (plan 33 Phase 0 — behavior-preserving refactor). One instance is created per {@code run()} loop
 * iteration and is not reused across nodes.
 */
public final class ExecutionContext {

    private final ExecutionEngine engine;
    private final ProcessDefinition definition;
    private final FlowNode node;
    private final TokenRecord token;
    private final String businessKey;
    private final String stateId;
    private final Instant enteredAt;
    private Map<String, Object> vars;

    public ExecutionContext(
            ExecutionEngine engine,
            ProcessDefinition definition,
            FlowNode node,
            TokenRecord token,
            String businessKey,
            Map<String, Object> vars,
            String stateId,
            Instant enteredAt
    ) {
        this.engine = engine;
        this.definition = definition;
        this.node = node;
        this.token = token;
        this.businessKey = businessKey;
        this.vars = vars;
        this.stateId = stateId;
        this.enteredAt = enteredAt;
    }

    // -- context data -----------------------------------------------------------------------------

    public ProcessDefinition definition() {
        return definition;
    }

    public FlowNode node() {
        return node;
    }

    public TokenRecord token() {
        return token;
    }

    public String businessKey() {
        return businessKey;
    }

    /** Current variable snapshot — may reflect mutations the behavior applied via this context. */
    public Map<String, Object> vars() {
        return vars;
    }

    public void setVars(Map<String, Object> vars) {
        this.vars = vars;
    }

    public String stateId() {
        return stateId;
    }

    public Instant enteredAt() {
        return enteredAt;
    }

    // -- port / collaborator access ----------------------------------------------------------------

    public ExpressionEvaluator expressions() {
        return engine.expressions();
    }

    public ConnectorRegistry connectors() {
        return engine.connectors();
    }

    public InstanceRepositoryPort instances() {
        return engine.instances();
    }

    public TokenRepositoryPort tokens() {
        return engine.tokens();
    }

    public VariableStorePort variables() {
        return engine.variables();
    }

    public TaskRepositoryPort tasks() {
        return engine.tasks();
    }

    public JobRepositoryPort jobs() {
        return engine.jobs();
    }

    public JobQueuePort jobQueue() {
        return engine.jobQueue();
    }

    public ClockPort clock() {
        return engine.clock();
    }

    public ObjectMapper json() {
        return engine.json();
    }

    public ExecutionLogPort execLog() {
        return engine.execLog();
    }

    public TokenStatePort tokenState() {
        return engine.tokenState();
    }

    public boolean asyncServiceTasks() {
        return engine.asyncServiceTasks();
    }

    public DefinitionLookupPort definitionLookup() {
        return engine.definitionLookup();
    }

    public SpawnGuardPort spawnGuard() {
        return engine.spawnGuard();
    }

    public EventSubscriptionPort eventSubscriptions() {
        return engine.eventSubscriptions();
    }

    // -- engine helper delegates (old-engine private methods, now public — see ExecutionEngine) ----

    /** Re-fetches {@link #vars()} from the variable store (old-engine's post-mutation {@code vars = variables.getAll(...)} refetch). */
    public void refreshVars() {
        this.vars = engine.variables().getAll(token.instanceId());
    }

    /** Applies IO input mappings then refreshes {@link #vars()} — matches old-engine's inline behavior. */
    public void applyIoInputs(List<IoParameter> inputs) {
        engine.applyIoInputs(inputs, token.instanceId(), vars);
        refreshVars();
    }

    /** Applies IO output mappings then refreshes {@link #vars()} — matches old-engine's inline behavior. */
    public void applyIoOutputs(List<IoParameter> outputs) {
        engine.applyIoOutputs(outputs, token.instanceId(), vars);
        refreshVars();
    }

    /** Runs the connector synchronously then refreshes {@link #vars()} (old-engine: {@code vars = variables.getAll(...)} right after). */
    public void executeConnector(String connectorId, Map<String, Object> inputs) {
        engine.executeConnector(token, businessKey, connectorId, inputs);
        refreshVars();
    }

    public void enqueueServiceTask(String connectorId, Map<String, Object> inputs) {
        engine.enqueueServiceTask(token, businessKey, connectorId, inputs);
    }

    /** Like {@link #executeConnector} but returns the failure instead of throwing — used when an error-boundary event can catch it. */
    public String tryExecuteConnector(String connectorId, Map<String, Object> inputs) {
        String error = engine.tryExecuteConnector(token, businessKey, connectorId, inputs);
        refreshVars();
        return error;
    }

    /** Persists a PENDING TIMER job for {@code runAt} — see {@code ExecutionEngine.enqueueTimerJob} (plan 32 Phase 2). */
    public String enqueueTimerJob(String nodeId, String subscriptionId, Instant runAt) {
        return engine.enqueueTimerJob(token, businessKey, nodeId, subscriptionId, runAt);
    }

    /** Parks {@link #token()} WAITING on a timer/message/signal catch (plan 32 Phase 2). */
    public void parkWaiting() {
        engine.parkTokenWaiting(token);
    }

    /** Correlates a MESSAGE — resumes every open matching subscription. See {@code ExecutionEngine.correlateMessage}. */
    public int correlateMessage(String messageName, Map<String, Object> vars) {
        return engine.correlateMessage(messageName, vars);
    }

    /** Broadcasts a SIGNAL — resumes every open matching subscription. See {@code ExecutionEngine.broadcastSignal}. */
    public int broadcastSignal(String signalName, Map<String, Object> vars) {
        return engine.broadcastSignal(signalName, vars);
    }

    public void terminateInstance() {
        engine.terminateInstance(token.instanceId());
    }

    public void maybeCompleteInstance() {
        engine.maybeCompleteInstance(token.instanceId());
    }

    /** Fires AFTER-phase listeners and closes this node's execution_token_state row — uses ctx's own node/stateId/vars. */
    public void completeNodeState() {
        engine.completeNodeState(stateId, node, vars, token.instanceId(), token.currentNodeId(), enteredAt);
    }

    public void fireListeners(String phase) {
        engine.fireListeners(node, stateId, phase, vars, token.instanceId(), token.currentNodeId());
    }

    /** Marks {@link #token()} COMPLETED and completes the instance if every token is done. */
    public void close() {
        engine.close(token);
    }

    public InstanceRecord withStatus(String instanceId, InstanceStatus status) {
        return engine.withStatus(instanceId, status);
    }

    public void logGateway(
            ExclusiveGatewayNode gateway, SequenceFlow chosen, List<SequenceFlow> matched, boolean usedDefault
    ) {
        engine.logGateway(token, gateway, chosen, matched, usedDefault);
    }

    public void markFailed(String message) {
        engine.markFailed(token, message);
    }

    /** Runs a sibling token to completion on this call stack — parallel/inclusive fork (plan 22/33). */
    public void runSibling(ProcessDefinition definition, TokenRecord token, String businessKey) {
        engine.run(definition, token, businessKey);
    }
}
