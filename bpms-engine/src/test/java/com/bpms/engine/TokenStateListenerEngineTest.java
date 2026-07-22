package com.bpms.engine;

import com.bpms.core.definition.ConnectorBinding;
import com.bpms.core.definition.ConnectorImplementation;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ListenerImplKind;
import com.bpms.core.definition.ListenerKind;
import com.bpms.core.definition.ListenerSpec;
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
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.ListenerLogPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.TokenStatePort;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 23 DoD: 1 node with 2 start-listeners + 1 end-listener produces 3 execution_listener_log rows
 * and 1 execution_token_state row (ACTIVE -> COMPLETED); a listener failure marks it FAILED instead.
 */
class TokenStateListenerEngineTest {

    @Test
    void firesBeforeAndAfterListenersAndCompletesTokenState() {
        CapturingTokenStatePort tokenState = new CapturingTokenStatePort();
        CapturingListenerLogPort listenerLog = new CapturingListenerLogPort();
        InMemoryPorts ports = new InMemoryPorts();
        ExecutionEngine engine = new ExecutionEngine(
                connectorRegistry(), new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper(), NoOpExecutionLogPort.INSTANCE,
                tokenState, listenerLog);

        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        engine.run(process(twoStartOneEndListeners()), token, "bk");

        List<ListenerLogPort.ListenerLogEntry> scoreListeners = listenerLog.entries.stream()
                .filter(e -> "score".equals(e.nodeId()))
                .toList();
        assertEquals(3, scoreListeners.size());
        assertEquals(2, scoreListeners.stream().filter(e -> "BEFORE".equals(e.phase())).count());
        assertEquals(1, scoreListeners.stream().filter(e -> "AFTER".equals(e.phase())).count());
        assertTrue(scoreListeners.stream().allMatch(e -> "SUCCESS".equals(e.status())));

        List<CapturingTokenStatePort.Row> scoreStates = tokenState.rows.values().stream()
                .filter(r -> "score".equals(r.nodeId()))
                .toList();
        assertEquals(1, scoreStates.size());
        assertEquals("COMPLETED", scoreStates.getFirst().status());
    }

    @Test
    void listenerFailurePropagatesAndMarksTokenStateFailed() {
        CapturingTokenStatePort tokenState = new CapturingTokenStatePort();
        CapturingListenerLogPort listenerLog = new CapturingListenerLogPort();
        InMemoryPorts ports = new InMemoryPorts();
        ExecutionEngine engine = new ExecutionEngine(
                connectorRegistry(), new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper(), NoOpExecutionLogPort.INSTANCE,
                tokenState, listenerLog);

        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        // EXPRESSION listeners never throw (SpelExpressionEvaluator swallows errors -> null, old-engine
        // parity) — use a CLASS listener instead, which genuinely fails because class invocation isn't
        // implemented yet (see ExecutionEngine.invokeListener).
        List<ListenerSpec> failingStart = List.of(
                new ListenerSpec(ListenerKind.EXECUTION, "start", ListenerImplKind.CLASS,
                        "com.example.SomeListener", null, null, null, null));

        assertThrows(RuntimeException.class, () -> engine.run(process(failingStart), token, "bk"));

        List<ListenerLogPort.ListenerLogEntry> scoreListeners = listenerLog.entries.stream()
                .filter(e -> "score".equals(e.nodeId()))
                .toList();
        assertEquals(1, scoreListeners.size());
        assertEquals("FAILED", scoreListeners.getFirst().status());
        assertTrue(scoreListeners.getFirst().errorMessage() != null);

        CapturingTokenStatePort.Row scoreState = tokenState.rows.values().stream()
                .filter(r -> "score".equals(r.nodeId()))
                .findFirst()
                .orElseThrow();
        assertEquals("FAILED", scoreState.status());
        assertTrue(scoreState.errorMessage() != null);
    }

    private static List<ListenerSpec> twoStartOneEndListeners() {
        return List.of(
                new ListenerSpec(ListenerKind.EXECUTION, "start", ListenerImplKind.EXPRESSION, null, "1+1", null, null, null),
                new ListenerSpec(ListenerKind.EXECUTION, "start", ListenerImplKind.EXPRESSION, null, "2+2", null, null, null),
                new ListenerSpec(ListenerKind.EXECUTION, "end", ListenerImplKind.EXPRESSION, null, "3+3", null, null, null)
        );
    }

    private static ProcessDefinition process(List<ListenerSpec> scoreListeners) {
        return new ProcessDefinition(
                "p", "p", "p",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new ServiceTaskNode("score", null,
                                new ConnectorImplementation(new ConnectorBinding(
                                        "NoopConnector", List.of(), List.of())),
                                Optional.empty(), scoreListeners),
                        new EndEventNode("done", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("toScore", null, "start", "score", Optional.empty()),
                        new SequenceFlow("toDone", null, "score", "done", Optional.empty())
                ),
                List.of(), List.of(), Map.of()
        );
    }

    private static ConnectorRegistry connectorRegistry() {
        return new ConnectorRegistry(List.of((ConnectorProvider) () -> List.of(new Connector() {
            @Override public String id() { return "NoopConnector"; }
            @Override public ConnectorResult execute(ConnectorContext ctx) { return ConnectorResult.ok(Map.of()); }
        })));
    }

    static final class CapturingTokenStatePort implements TokenStatePort {
        record Row(String id, String tokenId, String instanceId, String nodeId, String nodeType,
                   String status, String errorMessage) {}

        final Map<String, Row> rows = new java.util.LinkedHashMap<>();
        int seq = 0;

        @Override
        public String enter(String tokenId, String instanceId, String nodeId, String nodeType, Instant enteredAt) {
            String id = "state-" + (++seq);
            rows.put(id, new Row(id, tokenId, instanceId, nodeId, nodeType, "ACTIVE", null));
            return id;
        }

        @Override
        public void exit(String tokenStateId, String status, Instant exitedAt, Integer durationMs, String errorMessage) {
            Row r = rows.get(tokenStateId);
            rows.put(tokenStateId, new Row(r.id(), r.tokenId(), r.instanceId(), r.nodeId(), r.nodeType(), status, errorMessage));
        }

        @Override
        public Optional<String> activeStateId(String tokenId, String nodeId) {
            return rows.values().stream()
                    .filter(r -> r.tokenId().equals(tokenId) && r.nodeId().equals(nodeId) && "ACTIVE".equals(r.status()))
                    .map(Row::id)
                    .findFirst();
        }
    }

    static final class CapturingListenerLogPort implements ListenerLogPort {
        final List<ListenerLogEntry> entries = new ArrayList<>();
        @Override public void log(ListenerLogEntry entry) { entries.add(entry); }
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
