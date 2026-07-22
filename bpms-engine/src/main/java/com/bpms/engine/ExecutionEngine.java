package com.bpms.engine;

import com.bpms.core.definition.BoundaryEventNode;
import com.bpms.core.definition.BusinessRuleTaskNode;
import com.bpms.core.definition.CallActivityNode;
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
import com.bpms.engine.behavior.ExecutionContext;
import com.bpms.engine.behavior.NodeBehavior;
import com.bpms.engine.behavior.NodeBehaviorRegistry;
import com.bpms.engine.behavior.NodeResult;
import com.bpms.expression.HumanTaskExpressions;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorResult;
import com.bpms.spi.engine.RuntimeModels.EventSubscriptionRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.JobStatus;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;
import com.bpms.spi.expression.ExpressionEvaluator;
import com.bpms.spi.port.ClockPort;
import com.bpms.spi.port.DefinitionLookupPort;
import com.bpms.spi.port.EventSubscriptionPort;
import com.bpms.spi.port.ExecutionLogPort;
import com.bpms.spi.port.ExecutionLogPort.LogEntry;
import com.bpms.spi.port.IncidentPort;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.ListenerLogPort;
import com.bpms.spi.port.ListenerLogPort.ListenerLogEntry;
import com.bpms.spi.port.SpawnGuardPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TerminationSignal;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.TokenStatePort;
import com.bpms.spi.port.VariableStorePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
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
    private final NodeBehaviorRegistry behaviors;
    private final DefinitionLookupPort definitionLookup;
    private final SpawnGuardPort spawnGuard;
    private final EventSubscriptionPort eventSubscriptions;

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
            int maxNodeRevisitsPerRun,
            DefinitionLookupPort definitionLookup,
            SpawnGuardPort spawnGuard,
            EventSubscriptionPort eventSubscriptions
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
        this.definitionLookup = Objects.requireNonNullElse(definitionLookup, DefinitionLookupPort.EMPTY);
        this.spawnGuard = Objects.requireNonNullElse(spawnGuard, SpawnGuardPort.NOOP);
        this.eventSubscriptions = Objects.requireNonNullElse(eventSubscriptions, EventSubscriptionPort.NOOP);
        this.behaviors = NodeBehaviorRegistry.defaults();
    }

    /** Backward-compatible overload: no TIMER/MESSAGE/SIGNAL event-subscription wiring (plan 32 Phase 2 — defaults to a no-op port). */
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
            int maxNodeRevisitsPerRun,
            DefinitionLookupPort definitionLookup,
            SpawnGuardPort spawnGuard
    ) {
        this(connectors, expressions, instances, tokens, variables, tasks, jobs, jobQueue,
                clock, asyncServiceTasks, json, execLog, tokenState, listenerLog,
                termination, incidents, maxStepsPerRun, maxNodeRevisitsPerRun, definitionLookup, spawnGuard, null);
    }

    /** Backward-compatible overload: no callActivity spawn wiring (definitionLookup/spawnGuard default to no-op). */
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
        this(connectors, expressions, instances, tokens, variables, tasks, jobs, jobQueue,
                clock, asyncServiceTasks, json, execLog, tokenState, listenerLog,
                termination, incidents, maxStepsPerRun, maxNodeRevisitsPerRun, null, null);
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

    // -- accessors for com.bpms.engine.behavior.ExecutionContext (plan 33 Phase 0) -------------------

    public ConnectorRegistry connectors() {
        return connectors;
    }

    public ExpressionEvaluator expressions() {
        return expressions;
    }

    public InstanceRepositoryPort instances() {
        return instances;
    }

    public TokenRepositoryPort tokens() {
        return tokens;
    }

    public VariableStorePort variables() {
        return variables;
    }

    public TaskRepositoryPort tasks() {
        return tasks;
    }

    public JobRepositoryPort jobs() {
        return jobs;
    }

    public JobQueuePort jobQueue() {
        return jobQueue;
    }

    public ClockPort clock() {
        return clock;
    }

    public boolean asyncServiceTasks() {
        return asyncServiceTasks;
    }

    public ObjectMapper json() {
        return json;
    }

    public ExecutionLogPort execLog() {
        return execLog;
    }

    public TokenStatePort tokenState() {
        return tokenState;
    }

    public DefinitionLookupPort definitionLookup() {
        return definitionLookup;
    }

    public SpawnGuardPort spawnGuard() {
        return spawnGuard;
    }

    public EventSubscriptionPort eventSubscriptions() {
        return eventSubscriptions;
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

                ExecutionContext ctx = new ExecutionContext(this, definition, node, at, businessKey, vars, stateId, enteredAt);
                NodeBehavior<FlowNode> behavior = behaviors.get(node);
                NodeResult result = behavior.execute(node, ctx);

                switch (result) {
                    case NodeResult.Waiting w -> {
                        return;
                    }
                    case NodeResult.Finished f -> {
                        return;
                    }
                    case NodeResult.Continue c -> current = c.next();
                    case NodeResult.ContinueWithSiblings cs -> {
                        current = cs.next();
                        for (TokenRecord sibling : cs.siblings()) {
                            run(definition, sibling, businessKey);
                        }
                    }
                    case NodeResult.TakeFlows tf -> {
                        List<SequenceFlow> outgoing = tf.flows();
                        Map<String, Object> finalVars = ctx.vars();
                        if (outgoing.isEmpty()) {
                            completeNodeState(stateId, node, finalVars, at.instanceId(), nodeId, enteredAt);
                            close(at);
                            return;
                        }
                        if (outgoing.size() > 1) {
                            completeNodeState(stateId, node, finalVars, at.instanceId(), nodeId, enteredAt);
                            for (int i = 1; i < outgoing.size(); i++) {
                                tokens.save(new TokenRecord(
                                        UUID.randomUUID().toString(), at.instanceId(),
                                        outgoing.get(i).targetRef(), TokenStatus.ACTIVE, at.parentMultiInstanceId()));
                            }
                            current = new TokenRecord(
                                    at.id(), at.instanceId(),
                                    outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, at.parentMultiInstanceId());
                            tokens.save(current);
                            for (int i = 1; i < outgoing.size(); i++) {
                                final String siblingNode = outgoing.get(i).targetRef();
                                TokenRecord sibling = tokens.findByInstanceId(at.instanceId()).stream()
                                        .filter(t -> siblingNode.equals(t.currentNodeId()) && t.status() == TokenStatus.ACTIVE)
                                        .findFirst()
                                        .orElseThrow();
                                run(definition, sibling, businessKey);
                            }
                        } else {
                            completeNodeState(stateId, node, finalVars, at.instanceId(), nodeId, enteredAt);
                            current = new TokenRecord(
                                    at.id(), at.instanceId(),
                                    outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, at.parentMultiInstanceId());
                            tokens.save(current);
                        }
                    }
                }
            } catch (RuntimeException e) {
                tokenState.exit(stateId, "FAILED", clock.now(), durationMs(enteredAt), e.getMessage());
                throw e;
            }
        }
    }

    /** Called after a userTask is completed externally — fires AFTER listeners and advances. */
    public void continueAfterUserTask(ProcessDefinition definition, TokenRecord waitingToken, String businessKey) {
        FlowNode node = definition.node(waitingToken.currentNodeId()).orElseThrow();
        Map<String, Object> vars = variables.getAll(waitingToken.instanceId());
        if (node instanceof UserTaskNode user) {
            applyIoOutputs(user.outputs(), waitingToken.instanceId(), vars);
            vars = variables.getAll(waitingToken.instanceId());
        }
        // plan 32 Phase 3: the activity finished on its own — any boundary timer/message/signal watching it is moot.
        eventSubscriptions.deleteByInstanceAndNode(waitingToken.instanceId(), node.id());
        tokenState.activeStateId(waitingToken.id(), node.id()).ifPresent(stateId -> {
            try {
                fireListeners(node, stateId, "AFTER", variables.getAll(waitingToken.instanceId()),
                        waitingToken.instanceId(), node.id());
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
                    TokenStatus.ACTIVE, waitingToken.parentMultiInstanceId()));
            return;
        }
        TokenRecord next = new TokenRecord(
                waitingToken.id(), waitingToken.instanceId(),
                outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, waitingToken.parentMultiInstanceId());
        tokens.save(next);
        instances.save(withStatus(waitingToken.instanceId(), InstanceStatus.RUNNING));
        run(definition, next, businessKey);
    }

    /** BPMN terminateEndEvent: cancel every live token and complete the instance (≠ admin TERMINATE). */
    public void terminateInstance(String instanceId) {
        for (TokenRecord t : tokens.findByInstanceId(instanceId)) {
            if (t.status() == TokenStatus.ACTIVE
                    || t.status() == TokenStatus.WAITING
                    || t.status() == TokenStatus.WAITING_JOB) {
                tokens.save(new TokenRecord(
                        t.id(), t.instanceId(), t.currentNodeId(), TokenStatus.CANCELED, t.parentMultiInstanceId()));
            }
        }
        tasks.completeOpenTasks(instanceId, clock.now());
        InstanceRecord old = instances.findInstanceById(instanceId).orElseThrow();
        instances.save(new InstanceRecord(
                old.id(), old.definitionId(), old.businessKey(), InstanceStatus.COMPLETED,
                old.createdAt(), clock.now(), old.createdBy(), old.parentInstanceId(), old.rootInstanceId()));
        execLog.log(instanceEnd(instanceId, "OK", "terminateEndEvent"));
    }

    public void applyIoInputs(List<IoParameter> inputs, String instanceId, Map<String, Object> vars) {
        if (inputs == null || inputs.isEmpty()) {
            return;
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (IoParameter p : inputs) {
            if (p.name() == null || p.name().isBlank()) {
                continue;
            }
            if (p.value() != null) {
                mapped.put(p.name(), expressions.evaluate(p.value(), vars));
            } else if (!p.list().isEmpty()) {
                mapped.put(p.name(), p.list());
            } else if (!p.map().isEmpty()) {
                mapped.put(p.name(), p.map());
            }
        }
        if (!mapped.isEmpty()) {
            variables.putAll(instanceId, mapped);
        }
    }

    public void applyIoOutputs(List<IoParameter> outputs, String instanceId, Map<String, Object> vars) {
        applyIoInputs(outputs, instanceId, vars);
    }

    /** Called by JobQueue consumers after a serviceTask job finishes connector work. */
    public void continueAfterServiceTask(ProcessDefinition definition, TokenRecord waitingToken, String businessKey) {
        FlowNode node = definition.node(waitingToken.currentNodeId()).orElseThrow();
        Map<String, Object> vars = variables.getAll(waitingToken.instanceId());
        eventSubscriptions.deleteByInstanceAndNode(waitingToken.instanceId(), node.id());
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
                    TokenStatus.ACTIVE, waitingToken.parentMultiInstanceId()));
            return;
        }
        TokenRecord next = new TokenRecord(
                waitingToken.id(), waitingToken.instanceId(),
                outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, waitingToken.parentMultiInstanceId());
        tokens.save(next);
        instances.save(withStatus(waitingToken.instanceId(), InstanceStatus.RUNNING));
        run(definition, next, businessKey);
    }

    /**
     * Called after a timer/message/signal event catch (intermediate catch, {@code receiveTask}) has been
     * resolved externally — fires AFTER listeners and advances, exactly like {@link #continueAfterServiceTask}
     * (plan 32 Phase 2). The event's own subscription must already have been deleted by the caller
     * ({@link #resumeEventSubscription}) — this method only moves the token forward.
     */
    public void continueAfterEvent(ProcessDefinition definition, TokenRecord waitingToken, String businessKey) {
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
                    TokenStatus.ACTIVE, waitingToken.parentMultiInstanceId()));
            return;
        }
        TokenRecord next = new TokenRecord(
                waitingToken.id(), waitingToken.instanceId(),
                outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, waitingToken.parentMultiInstanceId());
        tokens.save(next);
        instances.save(withStatus(waitingToken.instanceId(), InstanceStatus.RUNNING));
        run(definition, next, businessKey);
    }

    /** Parks {@code token} WAITING on a timer/message/signal catch — mirrors userTask's WAITING convention (plan 32 Phase 2). */
    public void parkTokenWaiting(TokenRecord token) {
        tokens.save(new TokenRecord(
                token.id(), token.instanceId(), token.currentNodeId(), TokenStatus.WAITING, token.parentMultiInstanceId()));
        instances.save(withStatus(token.instanceId(), InstanceStatus.WAITING));
    }

    /**
     * Resolves a single {@code event_subscription} by id — the one entry point used by {@code
     * TimerJobHandler}, {@link #correlateMessage} and {@link #broadcastSignal} to wake whatever is
     * waiting on it (plan 32 Phases 2/3). Routes to {@link #fireBoundaryEvent} when the subscription was
     * registered by {@code BoundarySupport} (its {@code configJson} carries {@code "boundary":true});
     * otherwise treats it as a plain intermediate-catch/{@code receiveTask} wait and calls
     * {@link #continueAfterEvent}. A missing subscription (already fired/canceled) is a silent no-op —
     * callers (job retries, duplicate correlate calls) must be idempotent.
     */
    public void resumeEventSubscription(String subscriptionId, Map<String, Object> extraVars) {
        EventSubscriptionRecord sub = eventSubscriptions.findById(subscriptionId).orElse(null);
        if (sub == null) {
            return;
        }
        Map<String, Object> config = readConfig(sub.configJson());
        if (Boolean.TRUE.equals(config.get("boundary"))) {
            fireBoundaryEvent(sub, extraVars);
            return;
        }

        eventSubscriptions.deleteById(sub.id());
        InstanceRecord instance = instances.findInstanceById(sub.instanceId()).orElse(null);
        if (instance == null) {
            return;
        }
        if (extraVars != null && !extraVars.isEmpty()) {
            variables.putAll(sub.instanceId(), extraVars);
        }
        TokenRecord token = tokens.findTokenById(sub.tokenId()).orElse(null);
        if (token == null || token.status() == TokenStatus.CANCELED || token.status() == TokenStatus.COMPLETED) {
            return; // stale fire — the token already moved on (e.g. instance terminated concurrently)
        }
        ProcessDefinition def = definitionLookup.findDefinitionById(instance.definitionId())
                .orElseThrow(() -> new IllegalStateException("No definition for instance " + sub.instanceId()));
        continueAfterEvent(def, token, instance.businessKey());
    }

    /** Correlates a MESSAGE (1:1 catch — but every currently-open matching subscription is resumed; no businessKey filter yet). */
    public int correlateMessage(String messageName, Map<String, Object> vars) {
        return resumeAllMatching("MESSAGE", messageName, vars);
    }

    /** Broadcasts a SIGNAL — every open matching subscription is resumed (unlike message correlation, this is intentionally 1:N). */
    public int broadcastSignal(String signalName, Map<String, Object> vars) {
        return resumeAllMatching("SIGNAL", signalName, vars);
    }

    private int resumeAllMatching(String type, String name, Map<String, Object> vars) {
        List<EventSubscriptionRecord> subs = eventSubscriptions.findOpenByTypeAndName(type, name);
        for (EventSubscriptionRecord sub : subs) {
            resumeEventSubscription(sub.id(), vars);
        }
        return subs.size();
    }

    /**
     * Fires a boundary event subscription (plan 32 Phase 3): {@code sub.tokenId()} is the activity's own
     * token (still parked there), {@code sub.nodeId()} is the attached activity's id, and {@code
     * sub.configJson()} carries the boundary event's own node id + its {@code cancelActivity} flag (set by
     * {@code BoundarySupport} when the activity was entered).
     *
     * <ul>
     *   <li><b>Interrupting:</b> the activity token is CANCELED, every other boundary subscription for the
     *       same activity token is dropped (they no longer apply), any open userTask tied to that token is
     *       completed, and a NEW token is placed on the boundary node and run — takes the boundary's own
     *       outgoing flows via {@code BoundaryEventBehavior} (TakeFlows).</li>
     *   <li><b>Non-interrupting:</b> the activity token is left untouched; a sibling token is placed on the
     *       boundary node and run — the activity keeps executing on its own token.</li>
     * </ul>
     */
    public void fireBoundaryEvent(EventSubscriptionRecord sub, Map<String, Object> extraVars) {
        InstanceRecord instance = instances.findInstanceById(sub.instanceId()).orElse(null);
        if (instance == null) {
            eventSubscriptions.deleteById(sub.id());
            return;
        }
        ProcessDefinition def = definitionLookup.findDefinitionById(instance.definitionId())
                .orElseThrow(() -> new IllegalStateException("No definition for instance " + sub.instanceId()));

        Map<String, Object> config = readConfig(sub.configJson());
        String boundaryNodeId = String.valueOf(config.getOrDefault("boundaryNodeId", sub.nodeId()));
        boolean interrupting = Boolean.TRUE.equals(config.get("interrupting"));
        BoundaryEventNode boundary = def.node(boundaryNodeId)
                .filter(BoundaryEventNode.class::isInstance)
                .map(BoundaryEventNode.class::cast)
                .orElseThrow(() -> new IllegalStateException("Boundary node not found: " + boundaryNodeId));

        TokenRecord activityToken = tokens.findTokenById(sub.tokenId()).orElse(null);
        if (activityToken == null || activityToken.status() == TokenStatus.CANCELED
                || activityToken.status() == TokenStatus.COMPLETED) {
            eventSubscriptions.deleteById(sub.id());
            return; // stale fire — the activity already finished/was canceled another way
        }

        if (extraVars != null && !extraVars.isEmpty()) {
            variables.putAll(sub.instanceId(), extraVars);
        }

        eventSubscriptions.deleteById(sub.id());
        if (interrupting) {
            eventSubscriptions.findSubscriptionsByInstanceId(sub.instanceId()).stream()
                    .filter(s -> sub.tokenId().equals(s.tokenId()))
                    .forEach(s -> eventSubscriptions.deleteById(s.id()));
            tasks.completeOpenTaskForToken(activityToken.id(), clock.now());
            tokens.save(new TokenRecord(
                    activityToken.id(), activityToken.instanceId(), activityToken.currentNodeId(),
                    TokenStatus.CANCELED, activityToken.parentMultiInstanceId()));
            TokenRecord rerouted = new TokenRecord(
                    UUID.randomUUID().toString(), sub.instanceId(), boundary.id(), TokenStatus.ACTIVE,
                    activityToken.parentMultiInstanceId());
            tokens.save(rerouted);
            instances.save(withStatus(sub.instanceId(), InstanceStatus.RUNNING));
            run(def, rerouted, instance.businessKey());
        } else {
            TokenRecord sibling = new TokenRecord(
                    UUID.randomUUID().toString(), sub.instanceId(), boundary.id(), TokenStatus.ACTIVE,
                    activityToken.parentMultiInstanceId());
            tokens.save(sibling);
            instances.save(withStatus(sub.instanceId(), InstanceStatus.RUNNING));
            run(def, sibling, instance.businessKey());
        }
    }

    /** Best-effort JSON→Map decode for {@code EventSubscriptionRecord.configJson()} — never throws (missing/invalid config ⇒ empty map). */
    private Map<String, Object> readConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(configJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
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

    /**
     * Like {@link #executeConnector} but never calls {@link #markFailed}/throws on failure — returns the
     * error message instead (or {@code null} on success) so the caller can decide what to do (plan 32
     * Phase 3: {@code ServiceTaskBehavior} uses this when an {@code errorEventDefinition} boundary is
     * attached, rerouting to the boundary flow instead of failing the token).
     */
    public String tryExecuteConnector(TokenRecord token, String businessKey, String connectorId, Map<String, Object> inputs) {
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
            execLog.log(new LogEntry(
                    token.instanceId(), token.id(), token.currentNodeId(), "serviceTask", connectorId,
                    "CONNECTOR_ERROR", "FAIL", e.getMessage(),
                    details(inputs, null), durationMs(t0), clock.now()));
            return e.getMessage() == null ? "connector error" : e.getMessage();
        }

        if (!result.success()) {
            execLog.log(new LogEntry(
                    token.instanceId(), token.id(), token.currentNodeId(), "serviceTask", connectorId,
                    "CONNECTOR_ERROR", "FAIL", result.errorMessage(),
                    details(inputs, result.outputs()), durationMs(t0), clock.now()));
            return result.errorMessage() == null ? "connector failed" : result.errorMessage();
        }

        variables.putAll(token.instanceId(), result.outputs());
        execLog.log(new LogEntry(
                token.instanceId(), token.id(), token.currentNodeId(), "serviceTask", connectorId,
                "CONNECTOR_END", "OK", null,
                details(inputs, result.outputs()), durationMs(t0), clock.now()));
        return null;
    }

    public void enqueueServiceTask(TokenRecord token, String businessKey, String connectorId, Map<String, Object> inputs) {
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

    /**
     * Persists a PENDING {@code TIMER} job for {@code runAt} (plan 32 Phase 2). Deliberately does NOT call
     * {@link JobQueuePort#enqueue} — unlike {@link #enqueueServiceTask} (meant to run immediately), a timer
     * job's whole point is to wait until {@code runAt}; nothing in this codebase yet polls {@code job} rows
     * for due work (no {@code @Scheduled} poller exists — see plan 28 §8), so today a TIMER job only fires
     * when something explicitly calls {@code TimerJobHandler.handle}/{@link #resumeEventSubscription} (a
     * future poller is the natural way to make this automatic in production).
     */
    public String enqueueTimerJob(TokenRecord token, String businessKey, String nodeId, String subscriptionId, Instant runAt) {
        try {
            String payload = json.writeValueAsString(Map.of(
                    "tokenId", token.id(),
                    "instanceId", token.instanceId(),
                    "businessKey", businessKey == null ? "" : businessKey,
                    "nodeId", nodeId == null ? "" : nodeId,
                    "subscriptionId", subscriptionId
            ));
            String jobId = UUID.randomUUID().toString();
            jobs.save(new JobRecord(
                    jobId, token.instanceId(), token.id(), "TIMER", payload, JobStatus.PENDING, 0, runAt));
            return jobId;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot enqueue timer job", e);
        }
    }

    /** Fires AFTER-phase listeners then closes the node's execution_token_state row as COMPLETED. */
    public void completeNodeState(
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
    public void fireListeners(
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

    public void logGateway(
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

    public void markFailed(TokenRecord token, String message) {
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

    public InstanceRecord withStatus(String id, InstanceStatus status) {
        InstanceRecord old = instances.findInstanceById(id).orElseThrow();
        return new InstanceRecord(
                old.id(), old.definitionId(), old.businessKey(), status, old.createdAt(),
                status == InstanceStatus.COMPLETED || status == InstanceStatus.FAILED ? clock.now() : old.endedAt(),
                old.createdBy(), old.parentInstanceId(), old.rootInstanceId());
    }

    public void close(TokenRecord token) {
        tokens.save(new TokenRecord(token.id(), token.instanceId(), token.currentNodeId(), TokenStatus.COMPLETED, null));
        maybeCompleteInstance(token.instanceId());
    }

    /**
     * Completes the instance once every token is done, then — plan 34 Phase 1 — if this instance was
     * spawned by a callActivity, wakes the parent's WAITING token so the parent process can advance.
     */
    public void maybeCompleteInstance(String instanceId) {
        // CANCELED counts as done — interrupting boundary events and terminateEndEvent leave sibling
        // tokens canceled while one branch reaches end; without this the instance never completes.
        boolean allDone = tokens.findByInstanceId(instanceId).stream()
                .allMatch(t -> t.status() == TokenStatus.COMPLETED
                        || t.status() == TokenStatus.FAILED
                        || t.status() == TokenStatus.CANCELED);
        if (allDone) {
            InstanceRecord completed = withStatus(instanceId, InstanceStatus.COMPLETED);
            instances.save(completed);
            execLog.log(instanceEnd(instanceId, "OK", null));
            if (completed.parentInstanceId() != null) {
                resumeParentAfterCallActivity(completed.parentInstanceId(), instanceId);
            }
        }
    }

    /**
     * Wakes a parent instance's callActivity token after its child ({@code childInstanceId}) has finished
     * running (plan 34 Phase 1). No-op if the parent has no token WAITING at a {@code callActivity} node —
     * covers the synchronous case where {@code CallActivityBehavior} itself already advances the parent
     * (the parent token is still ACTIVE, not WAITING, at that point) and the case where this is called
     * more than once for the same child (idempotent).
     */
    public void resumeParentAfterCallActivity(String parentInstanceId, String childInstanceId) {
        InstanceRecord parent = instances.findInstanceById(parentInstanceId).orElse(null);
        if (parent == null) {
            return;
        }
        ProcessDefinition parentDef = definitionLookup.findDefinitionById(parent.definitionId()).orElse(null);
        if (parentDef == null) {
            return;
        }
        TokenRecord waiting = tokens.findByInstanceId(parentInstanceId).stream()
                .filter(t -> t.status() == TokenStatus.WAITING)
                .filter(t -> parentDef.node(t.currentNodeId())
                        .filter(CallActivityNode.class::isInstance)
                        .isPresent())
                .findFirst()
                .orElse(null);
        if (waiting == null) {
            return;
        }

        variables.putAll(parentInstanceId, variables.getAll(childInstanceId));
        continueAfterCallActivity(parentDef, waiting, parent.businessKey());
    }

    /** Completes the callActivity node's token_state and advances the parent's token — mirrors {@link #continueAfterUserTask}. */
    private void continueAfterCallActivity(ProcessDefinition definition, TokenRecord waitingToken, String businessKey) {
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
                    TokenStatus.ACTIVE, waitingToken.parentMultiInstanceId()));
            return;
        }
        TokenRecord next = new TokenRecord(
                waitingToken.id(), waitingToken.instanceId(),
                outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, waitingToken.parentMultiInstanceId());
        tokens.save(next);
        instances.save(withStatus(waitingToken.instanceId(), InstanceStatus.RUNNING));
        run(definition, next, businessKey);
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
