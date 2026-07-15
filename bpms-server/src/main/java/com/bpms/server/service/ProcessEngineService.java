package com.bpms.server.service;

import com.bpms.core.definition.FlowNode;
import com.bpms.core.definition.ParseResult;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.StartEventNode;
import com.bpms.engine.ExecutionEngine;
import com.bpms.spi.engine.ProcessEnginePort;
import com.bpms.spi.engine.RuntimeModels.DefinitionRecord;
import com.bpms.spi.engine.RuntimeModels.DeployResult;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.InstanceView;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.bpms.spi.engine.RuntimeModels.TokenView;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;
import com.bpms.spi.parse.ProcessDefinitionParser;
import com.bpms.spi.port.ClockPort;
import com.bpms.spi.port.DefinitionRegistry;
import com.bpms.spi.port.DefinitionRepositoryPort;
import com.bpms.spi.port.ExecutionLogPort;
import com.bpms.spi.port.ExecutionLogPort.LogEntry;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.VariableStorePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@Transactional
public class ProcessEngineService implements ProcessEnginePort {

    private final ProcessDefinitionParser parser;
    private final DefinitionRegistry registry;
    private final DefinitionRepositoryPort definitions;
    private final InstanceRepositoryPort instances;
    private final TokenRepositoryPort tokens;
    private final VariableStorePort variables;
    private final TaskRepositoryPort tasks;
    private final ExecutionEngine engine;
    private final ClockPort clock;
    private final ExecutionLogPort execLog;

    public ProcessEngineService(
            ProcessDefinitionParser parser,
            DefinitionRegistry registry,
            DefinitionRepositoryPort definitions,
            InstanceRepositoryPort instances,
            TokenRepositoryPort tokens,
            VariableStorePort variables,
            TaskRepositoryPort tasks,
            ExecutionEngine engine,
            ClockPort clock,
            ExecutionLogPort execLog
    ) {
        this.parser = parser;
        this.registry = registry;
        this.definitions = definitions;
        this.instances = instances;
        this.tokens = tokens;
        this.variables = variables;
        this.tasks = tasks;
        this.engine = engine;
        this.clock = clock;
        this.execLog = execLog;
    }

    @Override
    public DeployResult deploy(byte[] bytes) {
        ParseResult parsed = parser.parse(bytes);
        if (parsed.hasWarnings()) {
            throw new DeploymentWarningsException(parsed.warnings());
        }
        ProcessDefinition d = parsed.definition();
        String id = UUID.randomUUID().toString();
        int version = definitions.nextVersion(d.processId());
        definitions.save(new DefinitionRecord(
                id, d.processId(), d.name(), version, "CAMUNDA",
                new String(bytes, StandardCharsets.UTF_8), clock.now()));
        registry.warm(id, d);
        return new DeployResult(id, d.processId(), version);
    }

    @Override
    public InstanceView start(String ref, String businessKey, Map<String, Object> input) {
        DefinitionRecord d = definitions.findDefinitionById(ref)
                .or(() -> definitions.findLatestByKey(ref))
                .orElseThrow(() -> new NoSuchElementException("Definition not found: " + ref));
        ProcessDefinition model = registry.get(d.id());
        String iid = UUID.randomUUID().toString();
        instances.save(new InstanceRecord(iid, d.id(), businessKey, InstanceStatus.RUNNING, clock.now(), null));
        variables.putAll(iid, input == null ? Map.of() : input);
        Map<String, Object> startDetails = new java.util.LinkedHashMap<>();
        startDetails.put("variables", input == null ? Map.of() : new java.util.LinkedHashMap<>(input));
        execLog.log(new LogEntry(
                iid, null, null, null, null,
                "INSTANCE_START", "OK", businessKey,
                startDetails, null, clock.now()));
        StartEventNode start = model.nodes().stream()
                .filter(StartEventNode.class::isInstance)
                .map(StartEventNode.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Definition has no start event"));
        TokenRecord token = new TokenRecord(UUID.randomUUID().toString(), iid, start.id(), TokenStatus.ACTIVE, null);
        tokens.save(token);
        engine.run(model, token, businessKey);
        return getInstance(iid);
    }

    @Override
    public InstanceView getInstance(String id) {
        InstanceRecord i = instances.findInstanceById(id)
                .orElseThrow(() -> new NoSuchElementException("Instance not found: " + id));
        return new InstanceView(
                i.id(), i.definitionId(), i.businessKey(), i.status(),
                tokens.findByInstanceId(id).stream()
                        .map(t -> new TokenView(t.id(), t.currentNodeId(), t.status()))
                        .toList(),
                variables.getAll(id));
    }

    @Override
    public InstanceView completeTask(String taskId, Map<String, Object> input) {
        UserTaskRecord task = tasks.findTaskById(taskId)
                .orElseThrow(() -> new NoSuchElementException("Task not found: " + taskId));
        if (task.completed()) {
            throw new IllegalStateException("Task already completed");
        }
        variables.putAll(task.instanceId(), input == null ? Map.of() : input);
        tasks.save(new UserTaskRecord(
                task.id(), task.instanceId(), task.tokenId(), task.nodeId(), task.name(),
                true, task.createdAt(), clock.now()));
        InstanceRecord instance = instances.findInstanceById(task.instanceId()).orElseThrow();
        ProcessDefinition model = registry.get(instance.definitionId());
        TokenRecord waiting = tokens.findTokenById(task.tokenId()).orElseThrow();
        FlowNode node = model.node(waiting.currentNodeId()).orElseThrow();
        SequenceFlow next = model.outgoing(node.id()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("UserTask has no outgoing flow"));
        TokenRecord active = new TokenRecord(
                waiting.id(), waiting.instanceId(), next.targetRef(), TokenStatus.ACTIVE, null);
        tokens.save(active);
        instances.save(new InstanceRecord(
                instance.id(), instance.definitionId(), instance.businessKey(),
                InstanceStatus.RUNNING, instance.createdAt(), null));
        engine.run(model, active, instance.businessKey());
        return getInstance(instance.id());
    }
}
