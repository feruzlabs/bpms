package com.bpms.engine;

import com.bpms.core.definition.ConditionExpr;
import com.bpms.core.definition.ConnectorBinding;
import com.bpms.core.definition.ConnectorImplementation;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ExclusiveGatewayNode;
import com.bpms.core.definition.IoParameter;
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
import com.bpms.spi.port.ExecutionLogPort;
import com.bpms.spi.port.ExecutionLogPort.LogEntry;
import com.bpms.spi.port.InstanceRepositoryPort;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionLogEngineTest {

    @Test
    void logsConnectorEndWithErrMsgAndGatewayDefault() {
        CapturingLogPort logs = new CapturingLogPort();
        Connector scoring = new Connector() {
            @Override public String id() { return "GetScoringResultV9Connector"; }
            @Override public ConnectorResult execute(ConnectorContext ctx) {
                return ConnectorResult.ok(Map.of(
                        "isOk", false,
                        "errMsg", "Network is unreachable"));
            }
        };
        ConnectorRegistry registry = new ConnectorRegistry(List.of(
                (ConnectorProvider) () -> List.of(scoring)));

        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("token", "T1");
        ExecutionEngine engine = new ExecutionEngine(
                registry, new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper(), logs);

        String iid = "i1";
        ports.instances.put(iid, new InstanceRecord(iid, "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord token = new TokenRecord("t1", iid, "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        engine.run(scoreGatewayProcess(), token, "bk");

        assertTrue(logs.entries.stream().anyMatch(e -> "CONNECTOR_START".equals(e.eventType())));
        LogEntry end = logs.entries.stream()
                .filter(e -> "CONNECTOR_END".equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertEquals("OK", end.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) end.details().get("outputs");
        assertEquals(false, outputs.get("isOk"));
        assertEquals("Network is unreachable", outputs.get("errMsg"));

        LogEntry gw = logs.entries.stream()
                .filter(e -> "GATEWAY".equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertTrue(gw.message().contains("toRejected"));
        assertTrue(gw.message().contains("default") || Boolean.TRUE.equals(gw.details().get("default")));

        assertTrue(logs.entries.stream().anyMatch(e -> "INSTANCE_END".equals(e.eventType())));
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "rejected".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));
    }

    @Test
    void noOpLogPortDoesNotBreakExecution() {
        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("token", "T1");
        ConnectorRegistry registry = new ConnectorRegistry(List.of(
                (ConnectorProvider) () -> List.of(new Connector() {
                    @Override public String id() { return "GetScoringResultV9Connector"; }
                    @Override public ConnectorResult execute(ConnectorContext ctx) {
                        return ConnectorResult.ok(Map.of("isOk", true));
                    }
                })));
        ExecutionEngine engine = new ExecutionEngine(
                registry, new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper(), NoOpExecutionLogPort.INSTANCE);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        engine.run(scoreGatewayProcess(), token, "bk");
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "approved".equals(t.currentNodeId())));
    }

    private static ProcessDefinition scoreGatewayProcess() {
        return new ProcessDefinition(
                "p", "p", "p",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new ServiceTaskNode("score", null,
                                new ConnectorImplementation(new ConnectorBinding(
                                        "GetScoringResultV9Connector",
                                        List.of(new IoParameter("token", "#root['token']", null, null)),
                                        List.of())),
                                Optional.empty(), List.of()),
                        new ExclusiveGatewayNode("gw", null, "toRejected", Optional.empty(), List.of()),
                        new EndEventNode("approved", null, Optional.empty(), Optional.empty(), List.of()),
                        new EndEventNode("rejected", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("toScore", null, "start", "score", Optional.empty()),
                        new SequenceFlow("toGw", null, "score", "gw", Optional.empty()),
                        new SequenceFlow("toApproved", null, "gw", "approved",
                                Optional.of(new ConditionExpr("#root['isOk'] == true"))),
                        new SequenceFlow("toRejected", null, "gw", "rejected", Optional.empty())
                ),
                List.of(), List.of(), Map.of()
        );
    }

    static final class CapturingLogPort implements ExecutionLogPort {
        final List<LogEntry> entries = new CopyOnWriteArrayList<>();
        @Override public void log(LogEntry entry) { entries.add(entry); }
        @Override public List<LogEntry> byInstance(String instanceId) {
            return entries.stream().filter(e -> instanceId.equals(e.instanceId())).toList();
        }
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
