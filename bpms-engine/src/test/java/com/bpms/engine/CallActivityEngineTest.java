package com.bpms.engine;

import com.bpms.core.definition.CallActivityNode;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.StartEventNode;
import com.bpms.core.definition.UserTaskNode;
import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;
import com.bpms.spi.port.DefinitionLookupPort;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.SpawnGuardPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TerminationSignal;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 34 Phase 1: {@code callActivity} spawns a child process instance, runs it synchronously, and
 * either advances immediately (child finishes within the same call) or parks the parent token WAITING
 * until the child completes later ({@code ExecutionEngine.resumeParentAfterCallActivity}).
 */
class CallActivityEngineTest {

    @Test
    void parentAdvancesImmediatelyWhenChildCompletesSynchronously() {
        ProcessDefinition child = childProcessNoWait();
        ProcessDefinition parent = parentProcessCalling("childProc");

        InMemoryPorts ports = new InMemoryPorts();
        RecordingSpawnGuard spawnGuard = new RecordingSpawnGuard();
        MapDefinitionLookup lookup = new MapDefinitionLookup();
        lookup.register("childProc", "d-child", child);
        lookup.registerById("d-parent", parent);

        ports.instances.put("i1", new InstanceRecord(
                "i1", "d-parent", "bk", InstanceStatus.RUNNING, Instant.now(), null, "U1"));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        ExecutionEngine engine = newEngine(ports, lookup, spawnGuard);
        engine.run(parent, token, "bk");

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));

        InstanceRecord childInstance = ports.instances.values().stream()
                .filter(i -> "d-child".equals(i.definitionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("child instance not created"));
        assertEquals(InstanceStatus.COMPLETED, childInstance.status());
        assertEquals("i1", childInstance.parentInstanceId());
        assertEquals("i1", childInstance.rootInstanceId());

        assertEquals(1, spawnGuard.calls.size());
        assertEquals("i1", spawnGuard.calls.getFirst()[0]);
        assertEquals("i1", spawnGuard.calls.getFirst()[1]);
    }

    @Test
    void parentWaitsThenResumesWhenChildCompletesAsynchronously() {
        ProcessDefinition child = childProcessWithUserTask();
        ProcessDefinition parent = parentProcessCalling("childProc");

        InMemoryPorts ports = new InMemoryPorts();
        RecordingSpawnGuard spawnGuard = new RecordingSpawnGuard();
        MapDefinitionLookup lookup = new MapDefinitionLookup();
        lookup.register("childProc", "d-child", child);
        lookup.registerById("d-parent", parent);

        ports.instances.put("i1", new InstanceRecord(
                "i1", "d-parent", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        ExecutionEngine engine = newEngine(ports, lookup, spawnGuard);
        engine.run(parent, token, "bk");

        // Parent parked WAITING at the callActivity; child parked WAITING at its userTask.
        assertEquals(InstanceStatus.WAITING, ports.instances.get("i1").status());
        assertEquals(TokenStatus.WAITING, ports.tokens.get("t1").status());
        assertEquals("call", ports.tokens.get("t1").currentNodeId());

        InstanceRecord childInstance = ports.instances.values().stream()
                .filter(i -> "d-child".equals(i.definitionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("child instance not created"));
        assertEquals(InstanceStatus.WAITING, childInstance.status());
        assertEquals("i1", childInstance.parentInstanceId());
        assertEquals("i1", childInstance.rootInstanceId());
        assertEquals(1, spawnGuard.calls.size());

        UserTaskRecord childTask = ports.tasks.values().iterator().next();
        assertFalse(childTask.completed());

        // Complete the child's userTask externally — this drives the child to its endEvent, which
        // completes the child instance and must wake the parent's WAITING callActivity token.
        TokenRecord childToken = ports.tokens.get(childTask.tokenId());
        ports.tasks.put(childTask.id(), new UserTaskRecord(
                childTask.id(), childTask.instanceId(), childTask.tokenId(), childTask.nodeId(), childTask.name(),
                childTask.assignee(), childTask.candidateGroups(), childTask.candidateUsers(),
                childTask.dueDate(), childTask.priority(), childTask.formKey(), null, null,
                true, childTask.createdAt(), Instant.now()));
        engine.continueAfterUserTask(child, childToken, "bk");

        assertEquals(InstanceStatus.COMPLETED, findChildInstance(ports).status());
        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "i1".equals(t.instanceId()) && "end".equals(t.currentNodeId())
                        && t.status() == TokenStatus.COMPLETED));
    }

    private static InstanceRecord findChildInstance(InMemoryPorts ports) {
        return ports.instances.values().stream()
                .filter(i -> "d-child".equals(i.definitionId()))
                .findFirst()
                .orElseThrow();
    }

    private static ProcessDefinition parentProcessCalling(String calledElement) {
        return new ProcessDefinition(
                "d-parent", "parent", "parent",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new CallActivityNode("call", "Call child", calledElement, Optional.empty(), List.of()),
                        new EndEventNode("end", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f1", null, "start", "call", Optional.empty()),
                        new SequenceFlow("f2", null, "call", "end", Optional.empty())
                ),
                List.of(), List.of(), Map.of());
    }

    private static ProcessDefinition childProcessNoWait() {
        return new ProcessDefinition(
                "d-child", "child", "child",
                List.of(
                        new StartEventNode("cstart", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new EndEventNode("cend", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(new SequenceFlow("cf1", null, "cstart", "cend", Optional.empty())),
                List.of(), List.of(), Map.of());
    }

    private static ProcessDefinition childProcessWithUserTask() {
        return new ProcessDefinition(
                "d-child", "child", "child",
                List.of(
                        new StartEventNode("cstart", null, Optional.empty(), Optional.empty(), null, Optional.empty(), List.of()),
                        new UserTaskNode("ut", "Approve", Optional.empty(), null, null, null, null, null,
                                List.of(), List.of(), Optional.empty(), List.of()),
                        new EndEventNode("cend", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("cf1", null, "cstart", "ut", Optional.empty()),
                        new SequenceFlow("cf2", null, "ut", "cend", Optional.empty())
                ),
                List.of(), List.of(), Map.of());
    }

    private static ExecutionEngine newEngine(
            InMemoryPorts ports, DefinitionLookupPort lookup, SpawnGuardPort spawnGuard
    ) {
        ConnectorRegistry registry = new ConnectorRegistry(List.of());
        return new ExecutionEngine(
                registry, new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper(),
                null, null, null, TerminationSignal.NEVER, null,
                ExecutionEngine.DEFAULT_MAX_STEPS_PER_RUN, ExecutionEngine.DEFAULT_MAX_NODE_REVISITS_PER_RUN,
                lookup, spawnGuard);
    }

    /** Test double: registers definitions by process key (+ DB id) and by DB id — mirrors EngineConfig's real adapter. */
    static final class MapDefinitionLookup implements DefinitionLookupPort {
        final Map<String, ProcessDefinition> byKey = new HashMap<>();
        final Map<String, String> idByKey = new HashMap<>();
        final Map<String, ProcessDefinition> byId = new HashMap<>();

        void register(String key, String definitionId, ProcessDefinition definition) {
            byKey.put(key, definition);
            idByKey.put(key, definitionId);
            byId.put(definitionId, definition);
        }

        void registerById(String definitionId, ProcessDefinition definition) {
            byId.put(definitionId, definition);
        }

        @Override
        public Optional<ProcessDefinition> findDefinitionByKey(String processKey) {
            return Optional.ofNullable(byKey.get(processKey));
        }

        @Override
        public Optional<String> findDefinitionIdByKey(String processKey) {
            return Optional.ofNullable(idByKey.get(processKey));
        }

        @Override
        public Optional<ProcessDefinition> findDefinitionById(String definitionId) {
            return Optional.ofNullable(byId.get(definitionId));
        }
    }

    static final class RecordingSpawnGuard implements SpawnGuardPort {
        final List<String[]> calls = new ArrayList<>();

        @Override
        public void checkBeforeSpawn(String parentInstanceId, String rootInstanceId) {
            calls.add(new String[]{parentInstanceId, rootInstanceId});
        }
    }

    /** Per-instance variable storage (unlike the single-instance tests elsewhere) — needed since this
     *  test runs TWO instances (parent + child) concurrently and must not let their variables collide. */
    static final class InMemoryPorts implements InstanceRepositoryPort, TokenRepositoryPort, VariableStorePort,
            TaskRepositoryPort, JobRepositoryPort {
        final Map<String, InstanceRecord> instances = new ConcurrentHashMap<>();
        final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> variablesByInstance = new ConcurrentHashMap<>();
        final Map<String, UserTaskRecord> tasks = new ConcurrentHashMap<>();
        final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();

        @Override public InstanceRecord save(InstanceRecord i) { instances.put(i.id(), i); return i; }
        @Override public Optional<InstanceRecord> findInstanceById(String id) { return Optional.ofNullable(instances.get(id)); }
        @Override public TokenRecord save(TokenRecord t) { tokens.put(t.id(), t); return t; }
        @Override public List<TokenRecord> findByInstanceId(String id) {
            return tokens.values().stream().filter(t -> t.instanceId().equals(id)).toList();
        }
        @Override public Optional<TokenRecord> findTokenById(String id) { return Optional.ofNullable(tokens.get(id)); }
        @Override public void putAll(String id, Map<String, Object> values) {
            // Plain HashMap (not ConcurrentHashMap) per instance — VariableStorePort allows null values
            // (e.g. explicit reject signals), which ConcurrentHashMap.putAll would NPE on.
            Map<String, Object> bucket = variablesByInstance.computeIfAbsent(id, k -> new HashMap<>());
            values.forEach(bucket::put);
        }
        @Override public Map<String, Object> getAll(String id) {
            return new HashMap<>(variablesByInstance.getOrDefault(id, Map.of()));
        }
        @Override public UserTaskRecord save(UserTaskRecord t) { tasks.put(t.id(), t); return t; }
        @Override public Optional<UserTaskRecord> findTaskById(String id) { return Optional.ofNullable(tasks.get(id)); }
        @Override public void completeOpenTasks(String instanceId, Instant at) {
            tasks.replaceAll((k, t) -> t.instanceId().equals(instanceId) && !t.completed()
                    ? new UserTaskRecord(t.id(), t.instanceId(), t.tokenId(), t.nodeId(), t.name(),
                    t.assignee(), t.candidateGroups(), t.candidateUsers(), t.dueDate(), t.priority(),
                    t.formKey(), t.submittedData(), t.claimTime(), true, t.createdAt(), at)
                    : t);
        }
        @Override public JobRecord save(JobRecord job) { jobs.put(job.id(), job); return job; }
        @Override public Optional<JobRecord> findJobById(String id) { return Optional.ofNullable(jobs.get(id)); }
        @Override public List<JobRecord> findPendingByInstance(String instanceId) { return List.of(); }
    }
}
