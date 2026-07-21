package com.bpms.engine;

import com.bpms.core.definition.EmptyImplementation;
import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.ExclusiveGatewayNode;
import com.bpms.core.definition.FlowNode;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.ServiceTaskNode;
import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.parser.camunda.CamundaCompatParser;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Plan 22 / 29: real 4888 merge gateway {@code Gateway_0tgpe7b} must pass through unconditional
 * {@code Flow_1xqadt9} to {@code Activity_1ft8j53} (not die at gateway with COMPLETED).
 */
class Gateway4888RegressionTest {

    private static final String MERGE_GATEWAY = "Gateway_0tgpe7b";
    private static final String TARGET_AFTER_GATEWAY = "Activity_1ft8j53";
    private static final String UNCONDITIONAL_FLOW = "Flow_1xqadt9";

    private final CamundaCompatParser parser = new CamundaCompatParser();

    @Test
    void mergeGatewayPassesThroughUnconditionalFlow() throws Exception {
        Path bpmn = locate4888();
        Assumptions.assumeTrue(Files.isRegularFile(bpmn), "4888 BPMN not found: " + bpmn);

        ProcessDefinition full = parser.parse(Files.readAllBytes(bpmn)).definition();
        FlowNode gatewayNode = full.node(MERGE_GATEWAY).orElseThrow();
        Assumptions.assumeTrue(gatewayNode instanceof ExclusiveGatewayNode,
                MERGE_GATEWAY + " must be exclusive gateway in 4888");

        // Minimal slice: real gateway node from 4888 + unconditional flow → target activity → end.
        ProcessDefinition slice = new ProcessDefinition(
                "4888-gw-regression", "4888-gw-regression", "4888 gateway slice",
                List.of(
                        gatewayNode,
                        new ServiceTaskNode(TARGET_AFTER_GATEWAY, "after merge",
                                new EmptyImplementation(), Optional.empty(), List.of()),
                        new EndEventNode("end_reg", null, Optional.empty(), Optional.empty(), List.of())
                ),
                List.of(
                        new SequenceFlow(UNCONDITIONAL_FLOW, null, MERGE_GATEWAY, TARGET_AFTER_GATEWAY, Optional.empty()),
                        new SequenceFlow("f_end", null, TARGET_AFTER_GATEWAY, "end_reg", Optional.empty())
                ),
                List.of(), List.of(), Map.of());

        InMemoryPorts ports = new InMemoryPorts();
        ExecutionEngine engine = new ExecutionEngine(
                new ConnectorRegistry(List.of()), new SpelExpressionEvaluator(),
                ports, ports, ports, ports, ports, job -> {}, Instant::now, false, new ObjectMapper());

        TokenRecord token = new TokenRecord("t1", "i1", MERGE_GATEWAY, TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);
        ports.instances.put("i1", new InstanceRecord(
                "i1", slice.id(), "4888", InstanceStatus.RUNNING, Instant.now(), null));

        engine.run(slice, token, "4888");

        assertFalse(ports.tokens.values().stream()
                        .anyMatch(t -> MERGE_GATEWAY.equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED),
                "Token must not die at merge gateway (Flow_1xqadt9 is unconditional)");
        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertEquals("end_reg", ports.tokens.get("t1").currentNodeId());
    }

    private static Path locate4888() {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = {
                cwd.resolve("compat-corpus/credit-conveyor/TUNE_CREDIT_REQUEST_4888.bpmn"),
                cwd.resolve("../compat-corpus/credit-conveyor/TUNE_CREDIT_REQUEST_4888.bpmn"),
                cwd.resolve("../../compat-corpus/credit-conveyor/TUNE_CREDIT_REQUEST_4888.bpmn"),
                cwd.getParent().resolve("compat-corpus/credit-conveyor/TUNE_CREDIT_REQUEST_4888.bpmn")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        return candidates[1].normalize();
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
