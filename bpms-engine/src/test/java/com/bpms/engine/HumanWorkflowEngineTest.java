package com.bpms.engine;

import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ManualTaskNode;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.StartEventNode;
import com.bpms.core.definition.TerminateEventDef;
import com.bpms.core.definition.UserTaskNode;
import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.parser.camunda.CamundaCompatParser;
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

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 31: userTask wait/complete, terminateEnd, manualTask pass-through, vacation skeleton. */
class HumanWorkflowEngineTest {

    @Test
    void userTaskWaitsWithResolvedAssigneeThenCompleteAdvances() {
        ProcessDefinition def = new ProcessDefinition(
                "p", "p", "p",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), "starterUser",
                                Optional.empty(), List.of()),
                        new UserTaskNode("ut", "Approve", Optional.empty(),
                                "EMPLOYEE__$employee__empId", null, null, "$TASK_EXPIRED_DATE", "3",
                                List.of(), List.of(), Optional.empty(), List.of()),
                        new EndEventNode("end", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f1", null, "start", "ut", Optional.empty()),
                        new SequenceFlow("f2", null, "ut", "end", Optional.empty())
                ),
                List.of(), List.of(), Map.of());

        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("employee", Map.of("empId", "E-7"));
        ports.variables.put("TASK_EXPIRED_DATE", Instant.parse("2026-09-01T00:00:00Z"));
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null, "U1"));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        ExecutionEngine engine = newEngine(ports);
        engine.run(def, token, "bk");

        assertEquals(InstanceStatus.WAITING, ports.instances.get("i1").status());
        assertEquals(TokenStatus.WAITING, ports.tokens.get("t1").status());
        assertEquals(1, ports.tasks.size());
        UserTaskRecord task = ports.tasks.values().iterator().next();
        assertEquals("EMPLOYEE__E-7", task.assignee());
        assertEquals(3, task.priority());
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), task.dueDate());
        assertFalse(task.completed());

        ports.variables.put("sign", "ok");
        ports.tasks.put(task.id(), new UserTaskRecord(
                task.id(), task.instanceId(), task.tokenId(), task.nodeId(), task.name(),
                task.assignee(), task.candidateGroups(), task.candidateUsers(),
                task.dueDate(), task.priority(), task.formKey(), "{\"sign\":\"ok\"}", null,
                true, task.createdAt(), Instant.now()));
        engine.continueAfterUserTask(def, ports.tokens.get("t1"), "bk");

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));
    }

    @Test
    void terminateEndCancelsSiblingTokensAndCompletesInstance() {
        ProcessDefinition def = new ProcessDefinition(
                "p", "p", "p",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null,
                                Optional.empty(), List.of()),
                        new EndEventNode("kill", null, Optional.of(new TerminateEventDef()), Optional.empty(), List.of())
                ),
                List.of(new SequenceFlow("f1", null, "start", "kill", Optional.empty())),
                List.of(), List.of(), Map.of());

        InMemoryPorts ports = new InMemoryPorts();
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord main = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        TokenRecord sibling = new TokenRecord("t2", "i1", "somewhere", TokenStatus.WAITING, null);
        ports.tokens.put(main.id(), main);
        ports.tokens.put(sibling.id(), sibling);

        newEngine(ports).run(def, main, "bk");

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertEquals(TokenStatus.CANCELED, ports.tokens.get("t1").status());
        assertEquals(TokenStatus.CANCELED, ports.tokens.get("t2").status());
    }

    @Test
    void manualTaskIsPassThrough() {
        ProcessDefinition def = new ProcessDefinition(
                "p", "p", "p",
                List.of(
                        new StartEventNode("start", null, Optional.empty(), Optional.empty(), null,
                                Optional.empty(), List.of()),
                        new ManualTaskNode("mt", "Do offline", List.of(), List.of(), Optional.empty(), List.of()),
                        new EndEventNode("end", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow("f1", null, "start", "mt", Optional.empty()),
                        new SequenceFlow("f2", null, "mt", "end", Optional.empty())
                ),
                List.of(), List.of(), Map.of());

        InMemoryPorts ports = new InMemoryPorts();
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        newEngine(ports).run(def, token, "bk");

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "end".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));
    }

    @Test
    void vacationSkeletonApprovalChainCompletes() throws Exception {
        ProcessDefinition def = parseVacation();
        InMemoryPorts ports = new InMemoryPorts();
        Instant due = Instant.parse("2026-10-01T00:00:00Z");
        ports.variables.putAll(Map.of(
                "employee", Map.of("empId", "E1"),
                "head", Map.of("empId", "H1"),
                "TASK_EXPIRED_DATE", due,
                "starterUser", "U-init"));
        ports.instances.put("i1", new InstanceRecord(
                "i1", "d1", "vac-1", InstanceStatus.RUNNING, Instant.now(), null, "U-init"));
        TokenRecord token = new TokenRecord("t1", "i1", "Start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        ExecutionEngine engine = newEngine(ports);
        engine.run(def, token, "vac-1");

        UserTaskRecord emp = openTask(ports, "EmployeeTask");
        assertEquals("EMPLOYEE__E1", emp.assignee());
        assertEquals(due, emp.dueDate());
        assertEquals("PRIMARY", ports.variables.get("task_level"));

        complete(ports, engine, def, emp, Map.of("employee_sign", "yes"));

        UserTaskRecord head = openTask(ports, "HeadTask");
        assertEquals("EMPLOYEE__H1", head.assignee());
        complete(ports, engine, def, head, Map.of("head_sign", "yes"));

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "EndOk".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));
    }

    @Test
    void vacationSkeletonRejectTerminates() throws Exception {
        ProcessDefinition def = parseVacation();
        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.putAll(Map.of(
                "employee", Map.of("empId", "E1"),
                "head", Map.of("empId", "H1"),
                "TASK_EXPIRED_DATE", Instant.parse("2026-10-01T00:00:00Z")));
        ports.instances.put("i1", new InstanceRecord("i1", "d1", "vac-2", InstanceStatus.RUNNING, Instant.now(), null));
        TokenRecord token = new TokenRecord("t1", "i1", "Start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        ExecutionEngine engine = newEngine(ports);
        engine.run(def, token, "vac-2");

        UserTaskRecord emp = openTask(ports, "EmployeeTask");
        // omit employee_sign → explicit null → reject → terminateEnd
        Map<String, Object> reject = new HashMap<>();
        reject.put("employee_sign", null);
        complete(ports, engine, def, emp, reject);

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertTrue(ports.tokens.values().stream().allMatch(t ->
                t.status() == TokenStatus.CANCELED || t.status() == TokenStatus.COMPLETED));
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "EndReject".equals(t.currentNodeId()) && t.status() == TokenStatus.CANCELED));
    }

    private static void complete(
            InMemoryPorts ports, ExecutionEngine engine, ProcessDefinition def,
            UserTaskRecord task, Map<String, Object> vars
    ) {
        ports.variables.putAll(vars);
        ports.tasks.put(task.id(), new UserTaskRecord(
                task.id(), task.instanceId(), task.tokenId(), task.nodeId(), task.name(),
                task.assignee(), task.candidateGroups(), task.candidateUsers(),
                task.dueDate(), task.priority(), task.formKey(), null, null,
                true, task.createdAt(), Instant.now()));
        engine.continueAfterUserTask(def, ports.tokens.get(task.tokenId()), "bk");
    }

    private static UserTaskRecord openTask(InMemoryPorts ports, String nodeId) {
        return ports.tasks.values().stream()
                .filter(t -> nodeId.equals(t.nodeId()) && !t.completed())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No open task at " + nodeId));
    }

    private static ProcessDefinition parseVacation() throws Exception {
        try (InputStream in = HumanWorkflowEngineTest.class.getResourceAsStream("/fixtures/vacation-skeleton.bpmn")) {
            assertNotNull(in);
            return new CamundaCompatParser().parse(in.readAllBytes()).definition();
        }
    }

    private static ExecutionEngine newEngine(InMemoryPorts ports) {
        ConnectorRegistry registry = new ConnectorRegistry(List.of((ConnectorProvider) () -> List.of(new Connector() {
            @Override public String id() { return "noop"; }
            @Override public ConnectorResult execute(ConnectorContext context) { return ConnectorResult.ok(Map.of()); }
        })));
        return new ExecutionEngine(
                registry, new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper());
    }

    static final class InMemoryPorts implements InstanceRepositoryPort, TokenRepositoryPort, VariableStorePort,
            TaskRepositoryPort, JobRepositoryPort {
        final Map<String, InstanceRecord> instances = new ConcurrentHashMap<>();
        final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();
        final Map<String, Object> variables = new HashMap<>();
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
            values.forEach((k, v) -> {
                if (v == null) {
                    variables.put(k, null);
                } else {
                    variables.put(k, v);
                }
            });
        }
        @Override public Map<String, Object> getAll(String id) { return new HashMap<>(variables); }
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
