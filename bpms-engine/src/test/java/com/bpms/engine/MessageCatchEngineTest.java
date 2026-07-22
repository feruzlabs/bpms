package com.bpms.engine;

import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.ReceiveTaskNode;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.StartEventNode;
import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.spi.engine.RuntimeModels.EventSubscriptionRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;
import com.bpms.spi.port.DefinitionLookupPort;
import com.bpms.spi.port.EventSubscriptionPort;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 32 Phase 3: {@code receiveTask} parks the token WAITING behind a MESSAGE {@code
 * event_subscription}; {@code ExecutionEngine.correlateMessage} (the REST {@code
 * /api/v1/messages/correlate} endpoint's backing call) resumes every open matching subscription.
 */
class MessageCatchEngineTest {

    @Test
    void receiveTaskWaitsThenAdvancesOnCorrelate() {
        ProcessDefinition definition = receiveTaskProcess();
        InMemoryPorts ports = new InMemoryPorts();
        ExecutionEngine engine = newEngine(ports, definition);

        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        engine.run(definition, token, "bk");

        assertEquals(TokenStatus.WAITING, ports.tokens.get("t1").status());
        assertEquals("receive", ports.tokens.get("t1").currentNodeId());
        EventSubscriptionRecord sub = ports.eventSubscriptions.values().iterator().next();
        assertEquals("MESSAGE", sub.type());
        assertEquals("order-approved", sub.eventName());

        int resumed = engine.correlateMessage("order-approved", Map.of("approvedBy", "alice"));

        assertEquals(1, resumed);
        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));
        assertEquals("alice", ports.variables.get("approvedBy"));
        assertTrue(ports.eventSubscriptions.isEmpty());
    }

    @Test
    void correlateWithNoMatchingSubscriptionIsSoftNoOp() {
        InMemoryPorts ports = new InMemoryPorts();
        ExecutionEngine engine = newEngine(ports, receiveTaskProcess());

        int resumed = engine.correlateMessage("nobody-listening", Map.of());

        assertEquals(0, resumed);
    }

    private static ProcessDefinition receiveTaskProcess() {
        return new ProcessDefinition(
                "d1", "p", "p",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new ReceiveTaskNode("receive", null, "order-approved", Optional.empty(), List.of()),
                        new EndEventNode("end", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f1", null, "start", "receive", Optional.empty()),
                        new SequenceFlow("f2", null, "receive", "end", Optional.empty())
                ),
                List.of(), List.of(), Map.of());
    }

    private static ExecutionEngine newEngine(InMemoryPorts ports, ProcessDefinition definition) {
        ConnectorRegistry registry = new ConnectorRegistry(List.of());
        DefinitionLookupPort lookup = new DefinitionLookupPort() {
            @Override public Optional<ProcessDefinition> findDefinitionByKey(String processKey) { return Optional.empty(); }
            @Override public Optional<String> findDefinitionIdByKey(String processKey) { return Optional.empty(); }
            @Override public Optional<ProcessDefinition> findDefinitionById(String definitionId) {
                return definitionId.equals(definition.id()) ? Optional.of(definition) : Optional.empty();
            }
        };
        return new ExecutionEngine(
                registry, new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper(),
                null, null, null, com.bpms.spi.port.TerminationSignal.NEVER, null,
                ExecutionEngine.DEFAULT_MAX_STEPS_PER_RUN, ExecutionEngine.DEFAULT_MAX_NODE_REVISITS_PER_RUN,
                lookup, null, ports);
    }

    static final class InMemoryPorts implements InstanceRepositoryPort, TokenRepositoryPort, VariableStorePort,
            TaskRepositoryPort, JobRepositoryPort, EventSubscriptionPort {
        final Map<String, InstanceRecord> instances = new ConcurrentHashMap<>();
        final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();
        final Map<String, Object> variables = new ConcurrentHashMap<>();
        final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();
        final Map<String, EventSubscriptionRecord> eventSubscriptions = new ConcurrentHashMap<>();

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

        @Override public EventSubscriptionRecord save(EventSubscriptionRecord sub) { eventSubscriptions.put(sub.id(), sub); return sub; }
        @Override public void deleteById(String id) { eventSubscriptions.remove(id); }
        @Override public void deleteByInstanceId(String instanceId) {
            eventSubscriptions.values().removeIf(s -> s.instanceId().equals(instanceId));
        }
        @Override public void deleteByInstanceAndNode(String instanceId, String nodeId) {
            eventSubscriptions.values().removeIf(s -> s.instanceId().equals(instanceId) && nodeId.equals(s.nodeId()));
        }
        @Override public Optional<EventSubscriptionRecord> findById(String id) { return Optional.ofNullable(eventSubscriptions.get(id)); }
        @Override public List<EventSubscriptionRecord> findSubscriptionsByInstanceId(String instanceId) {
            return eventSubscriptions.values().stream().filter(s -> s.instanceId().equals(instanceId)).toList();
        }
        @Override public List<EventSubscriptionRecord> findOpenByTypeAndName(String type, String eventName) {
            return eventSubscriptions.values().stream()
                    .filter(s -> s.type().equals(type) && java.util.Objects.equals(s.eventName(), eventName))
                    .toList();
        }
    }
}
