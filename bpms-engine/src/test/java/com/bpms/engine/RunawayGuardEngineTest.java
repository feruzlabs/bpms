package com.bpms.engine;

import com.bpms.core.definition.EmptyImplementation;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.ServiceTaskNode;
import com.bpms.core.definition.StartEventNode;
import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;
import com.bpms.spi.port.IncidentPort;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TerminationSignal;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.VariableStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 27 §3b/§4: synchronous loop is guaranteed to be cut, and the cooperative stop signal is honored. */
class RunawayGuardEngineTest {

    /** A → B → A … a synchronous infinite loop (no end event). */
    private static ProcessDefinition loopProcess() {
        return new ProcessDefinition(
                "loop", "loop", "loop",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), Optional.empty(), List.of()),
                        new ServiceTaskNode("a", null, new EmptyImplementation(), Optional.empty(), List.of()),
                        new ServiceTaskNode("b", null, new EmptyImplementation(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f0", null, "start", "a", Optional.empty()),
                        new SequenceFlow("f1", null, "a", "b", Optional.empty()),
                        new SequenceFlow("f2", null, "b", "a", Optional.empty())
                ),
                List.of(), List.of(), Map.of());
    }

    @Test
    @Timeout(10) // must not hang: the guardrail has to break the loop
    void nodeRevisitCapCutsSynchronousLoopAndSuspends() {
        InMemoryPorts ports = new InMemoryPorts();
        RecordingIncidents incidents = new RecordingIncidents();
        // revisit cap trips before the raw step budget
        ExecutionEngine engine = engine(ports, TerminationSignal.NEVER, incidents, 100_000, 20);

        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(loopProcess(), token, "bk");

        assertEquals(InstanceStatus.SUSPENDED, ports.instances.get("i1").status());
        assertEquals(1, incidents.raised.size());
        assertEquals("LOOP_DETECTED", incidents.raised.getFirst().type());
    }

    @Test
    @Timeout(10)
    void stepBudgetCutsSynchronousLoopEvenWithoutRevisitTrip() {
        InMemoryPorts ports = new InMemoryPorts();
        RecordingIncidents incidents = new RecordingIncidents();
        // revisit cap very high so the raw step budget is what fires
        ExecutionEngine engine = engine(ports, TerminationSignal.NEVER, incidents, 50, 1_000_000);

        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(loopProcess(), token, "bk");

        assertEquals(InstanceStatus.SUSPENDED, ports.instances.get("i1").status());
        assertEquals("STEP_BUDGET_EXCEEDED", incidents.raised.getFirst().type());
    }

    @Test
    @Timeout(10)
    void cooperativeSignalCancelsTokenAndStops() {
        InMemoryPorts ports = new InMemoryPorts();
        RecordingIncidents incidents = new RecordingIncidents();
        // signal reports the instance halted → engine must cancel the token on the first checkpoint
        TerminationSignal halted = instanceId -> true;
        ExecutionEngine engine = engine(ports, halted, incidents, 100_000, 100_000);

        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(loopProcess(), token, "bk");

        assertEquals(TokenStatus.CANCELED, ports.tokens.get("t1").status());
        assertFalse(ports.instances.get("i1").status() == InstanceStatus.COMPLETED);
        assertTrue(incidents.raised.isEmpty()); // cooperative stop is not an incident
    }

    private static ExecutionEngine engine(
            InMemoryPorts ports, TerminationSignal signal, IncidentPort incidents,
            int maxSteps, int maxRevisits) {
        ConnectorRegistry registry = new ConnectorRegistry(List.of());
        return new ExecutionEngine(
                registry, new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper(),
                null, null, null, signal, incidents, maxSteps, maxRevisits);
    }

    record RaisedIncident(String instanceId, String type, String message) {}

    static final class RecordingIncidents implements IncidentPort {
        final List<RaisedIncident> raised = new ArrayList<>();
        @Override public String raise(String instanceId, String tokenId, String tokenStateId,
                                      String type, String severity, String message) {
            raised.add(new RaisedIncident(instanceId, type, message));
            return "inc-" + raised.size();
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
        @Override public List<JobRecord> findPendingByInstance(String instanceId) { return List.of(); }
    }
}
