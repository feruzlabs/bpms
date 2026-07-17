package com.bpms.engine;

import com.bpms.core.definition.ConditionExpr;
import com.bpms.core.definition.EmptyImplementation;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ExclusiveGatewayNode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusiveGatewayEngineTest {

    @Test
    void takesConditionBranchWhenHeuristicMatches() {
        ProcessDefinition definition = linearGatewayProcess("amount.intValue() > 100");

        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("amount", 150L);
        ExecutionEngine engine = newEngine(ports, false);
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(definition, token, "bk");

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertTrue(ports.tokens.values().stream().anyMatch(t -> "end_ok".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));
    }

    @Test
    void gatewayAcceptsDecimalAmountAsBigDecimal() {
        ProcessDefinition definition = linearGatewayProcess("amount.doubleValue() > 100");

        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("amount", new java.math.BigDecimal("150.50"));
        ExecutionEngine engine = newEngine(ports, false);
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(definition, token, "bk");

        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end_ok".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));
    }

    @Test
    void fallsBackToDefaultFlow() {
        ProcessDefinition definition = linearGatewayProcess("amount.intValue() > 100");

        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("amount", 10L);
        ExecutionEngine engine = newEngine(ports, false);
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(definition, token, "bk");

        assertTrue(ports.tokens.values().stream().anyMatch(t -> "end_low".equals(t.currentNodeId())));
    }

    private static ProcessDefinition linearGatewayProcess(String condition) {
        return new ProcessDefinition(
                "demo", "demo", "demo",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), Optional.empty(), List.of()),
                        new ServiceTaskNode("noop", null, new EmptyImplementation(), Optional.empty(), List.of()),
                        new ExclusiveGatewayNode("gw", null, "toLow", Optional.empty(), List.of()),
                        new EndEventNode("end_ok", null, Optional.empty(), Optional.empty(), List.of()),
                        new EndEventNode("end_low", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f1", null, "start", "noop", Optional.empty()),
                        new SequenceFlow("f2", null, "noop", "gw", Optional.empty()),
                        new SequenceFlow("toOk", null, "gw", "end_ok",
                                Optional.of(new ConditionExpr(condition))),
                        new SequenceFlow("toLow", null, "gw", "end_low", Optional.empty())
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
