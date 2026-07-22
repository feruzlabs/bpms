package com.bpms.engine;

import com.bpms.core.definition.ConditionExpr;
import com.bpms.core.definition.EmptyImplementation;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ExclusiveGatewayNode;
import com.bpms.core.definition.InclusiveGatewayNode;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.ServiceTaskNode;
import com.bpms.core.definition.StartEventNode;
import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorProvider;
import com.bpms.spi.connector.ConnectorResult;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;
import com.bpms.spi.port.ClockPort;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.VariableStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for fix #22: exclusive/inclusive gateway unconditional flow handling.
 * Ensures that shartsiz (unconditional) flows are treated as implicit defaults.
 */
class GatewayUnconditionalFlowTest {

    @Test
    void exclusiveMergeGatewayWithUnconditionalFlowPassesToken() {
        ProcessDefinition definition = mergeGatewayProcess();

        InMemoryPorts ports = new InMemoryPorts();
        ExecutionEngine engine = newEngine(ports, false);
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(definition, token, "bk");

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED),
                "Token should reach end node, not stay at gateway");
    }

    @Test
    void exclusiveGatewayFallsBackToUnconditionalFlowWhenNoConditionMatches() {
        ProcessDefinition definition = conditionalGatewayProcess();

        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("amount", 10L);
        ExecutionEngine engine = newEngine(ports, false);
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(definition, token, "bk");

        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end_default".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED),
                "Should fallback to unconditional flow when condition is false");
    }

    @Test
    void exclusiveGatewayUsesConditionBeforeUnconditional() {
        ProcessDefinition definition = conditionalGatewayProcess();

        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("amount", 150L);
        ExecutionEngine engine = newEngine(ports, false);
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(definition, token, "bk");

        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end_ok".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED),
                "Should use conditional branch when condition is true");
    }

    @Test
    void inclusiveGatewayTakesAllTrueConditionalBranches() {
        ProcessDefinition definition = inclusiveGatewayProcess();

        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("score", 80L);
        ExecutionEngine engine = newEngine(ports, false);
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(definition, token, "bk");

        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end_high".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED),
                "score>50 branch should complete");
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end_mid".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED),
                "score>30 branch should also complete (inclusive = all true)");
        assertFalse(ports.tokens.values().stream()
                .anyMatch(t -> "end_default".equals(t.currentNodeId())),
                "default branch should not run when conditions match");
    }

    @Test
    void inclusiveGatewayFallsBackToUnconditionalWhenNoConditionMatches() {
        ProcessDefinition definition = inclusiveGatewayProcess();

        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("score", 10L);
        ExecutionEngine engine = newEngine(ports, false);
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(definition, token, "bk");

        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end_default".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED),
                "Should fallback to unconditional when no condition is true");
        assertFalse(ports.tokens.values().stream()
                .anyMatch(t -> "end_high".equals(t.currentNodeId()) || "end_mid".equals(t.currentNodeId())),
                "Conditional branches should not run when score is too low");
    }

    /**
     * Merge gateway: start → noop → exclusive gw (1 unconditional outgoing) → end.
     * Token should pass through gateway to end node.
     */
    private static ProcessDefinition mergeGatewayProcess() {
        return new ProcessDefinition(
                "demo", "demo", "demo",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new ServiceTaskNode("noop", null, new EmptyImplementation(), Optional.empty(), List.of()),
                        new ExclusiveGatewayNode("gw", null, null, Optional.empty(), List.of()),
                        new EndEventNode("end", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f1", null, "start", "noop", Optional.empty()),
                        new SequenceFlow("f2", null, "noop", "gw", Optional.empty()),
                        new SequenceFlow("unconditional", null, "gw", "end", Optional.empty())
                ),
                List.of(), List.of(), Map.of()
        );
    }

    /**
     * Branch + default/unconditional:
     * start → noop → exclusive gw (condition + unconditional default) → end_ok or end_default.
     */
    private static ProcessDefinition conditionalGatewayProcess() {
        return new ProcessDefinition(
                "demo", "demo", "demo",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new ServiceTaskNode("noop", null, new EmptyImplementation(), Optional.empty(), List.of()),
                        new ExclusiveGatewayNode("gw", null, null, Optional.empty(), List.of()),
                        new EndEventNode("end_ok", null, Optional.empty(), Optional.empty(), List.of()),
                        new EndEventNode("end_default", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f1", null, "start", "noop", Optional.empty()),
                        new SequenceFlow("f2", null, "noop", "gw", Optional.empty()),
                        new SequenceFlow("toOk", null, "gw", "end_ok",
                                Optional.of(new ConditionExpr("amount.intValue() > 100"))),
                        new SequenceFlow("toDefault", null, "gw", "end_default", Optional.empty())
                ),
                List.of(), List.of(), Map.of()
        );
    }

    /** Inclusive: score>50 AND score>30 both true at score=80 → fork to end_high and end_mid. */
    private static ProcessDefinition inclusiveGatewayProcess() {
        return new ProcessDefinition(
                "demo", "demo", "demo",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new ServiceTaskNode("noop", null, new EmptyImplementation(), Optional.empty(), List.of()),
                        new InclusiveGatewayNode("gw", null, null, Optional.empty(), List.of()),
                        new EndEventNode("end_high", null, Optional.empty(), Optional.empty(), List.of()),
                        new EndEventNode("end_mid", null, Optional.empty(), Optional.empty(), List.of()),
                        new EndEventNode("end_default", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f1", null, "start", "noop", Optional.empty()),
                        new SequenceFlow("f2", null, "noop", "gw", Optional.empty()),
                        new SequenceFlow("toHigh", null, "gw", "end_high",
                                Optional.of(new ConditionExpr("score.intValue() > 50"))),
                        new SequenceFlow("toMid", null, "gw", "end_mid",
                                Optional.of(new ConditionExpr("score.intValue() > 30"))),
                        new SequenceFlow("toDefault", null, "gw", "end_default", Optional.empty())
                ),
                List.of(), List.of(), Map.of()
        );
    }

    private static ExecutionEngine newEngine(InMemoryPorts ports, boolean async) {
        ConnectorRegistry registry = new ConnectorRegistry(List.of((ConnectorProvider) () -> List.of(new Connector() {
            @Override public String id() { return "noop"; }
            @Override public ConnectorResult execute(ConnectorContext context) { return ConnectorResult.ok(Map.of()); }
        })));
        return new ExecutionEngine(
                registry, new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, async, new ObjectMapper());
    }

    static final class InMemoryPorts implements InstanceRepositoryPort, TokenRepositoryPort, VariableStorePort,
            TaskRepositoryPort, JobRepositoryPort {
        final Map<String, InstanceRecord> instances = new ConcurrentHashMap<>();
        final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();
        final Map<String, Object> variables = new ConcurrentHashMap<>();
        final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();

        @Override public InstanceRecord save(InstanceRecord i) { instances.put(i.id(), i); return i; }
        @Override public Optional<InstanceRecord> findInstanceById(String id) { return Optional.ofNullable(instances.get(id)); }
        @Override public TokenRecord save(TokenRecord t) { tokens.put(t.id(), t); return t; }
        @Override public List<TokenRecord> findByInstanceId(String id) {
            return tokens.values().stream().filter(t -> t.instanceId().equals(id)).toList();
        }
        @Override public Optional<TokenRecord> findTokenById(String id) { return Optional.ofNullable(tokens.get(id)); }
        @Override public void putAll(String id, Map<String, Object> values) { variables.putAll(values); }
        @Override public Map<String, Object> getAll(String id) { return new HashMap<>(variables); }
        @Override public UserTaskRecord save(UserTaskRecord t) { return t; }
        @Override public Optional<UserTaskRecord> findTaskById(String id) { return Optional.empty(); }
        @Override public JobRecord save(JobRecord job) { jobs.put(job.id(), job); return job; }
        @Override public Optional<JobRecord> findJobById(String id) { return Optional.ofNullable(jobs.get(id)); }
        @Override public java.util.List<JobRecord> findPendingByInstance(String instanceId) { return java.util.List.of(); }
    }
}
