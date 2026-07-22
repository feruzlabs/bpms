package com.bpms.engine;

import com.bpms.core.definition.BusinessRuleTaskNode;
import com.bpms.core.definition.ConnectorBinding;
import com.bpms.core.definition.ConnectorImplementation;
import com.bpms.core.definition.EmptyImplementation;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.SequenceFlow;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 32 Faza D: businessRuleTask — connector path now; DMN deferred. */
class BusinessRuleTaskEngineTest {

    @Test
    void connectorBusinessRuleTaskExecutesLikeServiceTask() {
        AtomicBoolean ran = new AtomicBoolean(false);
        ProcessDefinition def = new ProcessDefinition(
                "d1", "p", "p",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new BusinessRuleTaskNode("brt", "Decide",
                                new ConnectorImplementation(new ConnectorBinding("DecideConnector", List.of(), List.of())),
                                Optional.empty(), List.of()),
                        new EndEventNode("end", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f1", null, "start", "brt", Optional.empty()),
                        new SequenceFlow("f2", null, "brt", "end", Optional.empty())
                ),
                List.of(), List.of(), Map.of());

        InMemoryPorts ports = new InMemoryPorts();
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        ConnectorRegistry registry = new ConnectorRegistry(List.of((ConnectorProvider) () -> List.of(new Connector() {
            @Override public String id() { return "DecideConnector"; }
            @Override public ConnectorResult execute(ConnectorContext context) {
                ran.set(true);
                return ConnectorResult.ok(Map.of("limit", 1000L));
            }
        })));
        ExecutionEngine engine = new ExecutionEngine(
                registry, new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper());

        engine.run(def, token, "bk");

        assertTrue(ran.get());
        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertEquals(1000L, ports.variables.get("limit"));
    }

    @Test
    void emptyBusinessRuleTaskPassesThroughWithoutDmn() {
        ProcessDefinition def = new ProcessDefinition(
                "d1", "p", "p",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new BusinessRuleTaskNode("brt", "Decide", new EmptyImplementation(), Optional.empty(), List.of()),
                        new EndEventNode("end", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f1", null, "start", "brt", Optional.empty()),
                        new SequenceFlow("f2", null, "brt", "end", Optional.empty())
                ),
                List.of(), List.of(), Map.of());

        InMemoryPorts ports = new InMemoryPorts();
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        ExecutionEngine engine = new ExecutionEngine(
                new ConnectorRegistry(List.of()), new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper());

        engine.run(def, token, "bk");

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));
    }

    static final class InMemoryPorts implements InstanceRepositoryPort, TokenRepositoryPort, VariableStorePort,
            TaskRepositoryPort, JobRepositoryPort {
        final Map<String, InstanceRecord> instances = new ConcurrentHashMap<>();
        final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();
        final Map<String, Object> variables = new HashMap<>();
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
        @Override public List<JobRecord> findPendingByInstance(String instanceId) { return List.of(); }
    }
}
