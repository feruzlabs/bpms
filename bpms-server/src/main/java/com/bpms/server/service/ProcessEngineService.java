package com.bpms.server.service;

import com.bpms.core.definition.FlowNode;
import com.bpms.core.definition.FormDataSpec;
import com.bpms.core.definition.ParseResult;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.StartEventNode;
import com.bpms.core.definition.UserTaskNode;
import com.bpms.engine.ExecutionEngine;
import com.bpms.spi.engine.ProcessEnginePort;
import com.bpms.spi.engine.RuntimeModels.DefinitionRecord;
import com.bpms.spi.engine.RuntimeModels.DefinitionVersionView;
import com.bpms.spi.engine.RuntimeModels.DeployResult;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.InstanceView;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.JobStatus;
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
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.VariableStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

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
    private final JobRepositoryPort jobs;
    private final JobQueuePort jobQueue;
    private final ExecutionEngine engine;
    private final ClockPort clock;
    private final ExecutionLogPort execLog;
    private final ObjectMapper json;
    private final boolean dedupIdentical;

    public ProcessEngineService(
            ProcessDefinitionParser parser,
            DefinitionRegistry registry,
            DefinitionRepositoryPort definitions,
            InstanceRepositoryPort instances,
            TokenRepositoryPort tokens,
            VariableStorePort variables,
            TaskRepositoryPort tasks,
            JobRepositoryPort jobs,
            JobQueuePort jobQueue,
            ExecutionEngine engine,
            ClockPort clock,
            ExecutionLogPort execLog,
            ObjectMapper json,
            @Value("${bpms.versioning.dedup-identical:true}") boolean dedupIdentical
    ) {
        this.parser = parser;
        this.registry = registry;
        this.definitions = definitions;
        this.instances = instances;
        this.tokens = tokens;
        this.variables = variables;
        this.tasks = tasks;
        this.jobs = jobs;
        this.jobQueue = jobQueue;
        this.engine = engine;
        this.clock = clock;
        this.execLog = execLog;
        this.json = json;
        this.dedupIdentical = dedupIdentical;
    }

    @Override
    public DeployResult deploy(byte[] bytes) {
        ParseResult parsed = parser.parse(bytes);
        if (parsed.hasWarnings()) {
            throw new DeploymentWarningsException(parsed.warnings());
        }
        ProcessDefinition d = parsed.definition();
        String checksum = BpmnChecksum.sha256Canonical(bytes);
        String xml = new String(bytes, StandardCharsets.UTF_8);

        Optional<DefinitionRecord> latest = definitions.findLatestByKey(d.processId());
        if (dedupIdentical && latest.isPresent()
                && checksum != null && checksum.equals(latest.get().checksum())) {
            DefinitionRecord existing = latest.get();
            registry.warm(existing.id(), d);
            return new DeployResult(
                    existing.id(), existing.key(), existing.version(), true, false, checksum);
        }

        String id = UUID.randomUUID().toString();
        int version = definitions.nextVersion(d.processId());
        definitions.save(new DefinitionRecord(
                id, d.processId(), d.name(), version, "CAMUNDA",
                xml, clock.now(), checksum, true));
        registry.warm(id, d);
        return new DeployResult(id, d.processId(), version, true, true, checksum);
    }

    /** Catalog: all versions for a process key, or every definition when {@code key} is null/blank (plan 35). */
    public List<DefinitionVersionView> listDefinitions(String key) {
        if (key != null && !key.isBlank()) {
            return definitions.findVersionsByKey(key.trim());
        }
        return definitions.findAll().stream()
                .map(d -> new DefinitionVersionView(
                        d.id(), d.key(), d.name(), d.version(), d.isLatest(), d.checksum(),
                        d.createdAt(), "ACTIVE", 0L))
                .toList();
    }

    @Override
    public InstanceView start(String ref, String businessKey, Map<String, Object> input) {
        return start(ref, businessKey, input, null);
    }

    @Override
    public InstanceView start(String ref, String businessKey, Map<String, Object> input, String startedBy) {
        DefinitionRecord d = resolveDefinitionRef(ref);
        ProcessDefinition model = registry.get(d.id());
        Map<String, Object> values = new LinkedHashMap<>(StartFormValidator.validateAndCoerce(model, input));
        String bk = resolveBusinessKey(model, values, businessKey);

        StartEventNode start = model.nodes().stream()
                .filter(StartEventNode.class::isInstance)
                .map(StartEventNode.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Definition has no start event"));

        if (startedBy != null && !startedBy.isBlank()) {
            if (start.initiator() != null && !start.initiator().isBlank()) {
                values.put(start.initiator(), startedBy);
            }
        }

        String iid = UUID.randomUUID().toString();
        instances.save(new InstanceRecord(
                iid, d.id(), bk, InstanceStatus.RUNNING, clock.now(), null, startedBy,
                null, null, d.key(), d.version()));
        variables.putAll(iid, values);
        Map<String, Object> startDetails = new LinkedHashMap<>();
        startDetails.put("variables", new LinkedHashMap<>(values));
        if (startedBy != null) {
            startDetails.put("startedBy", startedBy);
        }
        execLog.log(new LogEntry(
                iid, null, null, null, null,
                "INSTANCE_START", "OK", bk,
                startDetails, null, clock.now()));

        TokenRecord token = new TokenRecord(UUID.randomUUID().toString(), iid, start.id(), TokenStatus.ACTIVE, null);
        tokens.save(token);

        try {
            String payload = json.writeValueAsString(Map.of(
                    "instanceId", iid,
                    "tokenId", token.id(),
                    "businessKey", bk == null ? "" : bk));
            JobRecord job = new JobRecord(
                    UUID.randomUUID().toString(), iid, token.id(),
                    StartProcessJobHandler.TYPE, payload, JobStatus.PENDING, 0, clock.now());
            jobs.save(job);
            jobQueue.enqueue(job);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot enqueue PROCESS_START", e);
        }

        return getInstance(iid);
    }

    /**
     * Resolves a start {@code ref}: definition id, process key (→ {@code is_latest}), or {@code key:version}
     * for an exact version (plan 35).
     */
    DefinitionRecord resolveDefinitionRef(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new NoSuchElementException("Definition ref is required");
        }
        Optional<DefinitionRecord> byId = definitions.findDefinitionById(ref);
        if (byId.isPresent()) {
            return byId.get();
        }
        int colon = ref.lastIndexOf(':');
        if (colon > 0 && colon < ref.length() - 1) {
            String key = ref.substring(0, colon);
            String verPart = ref.substring(colon + 1);
            try {
                int version = Integer.parseInt(verPart);
                return definitions.findByKeyAndVersion(key, version)
                        .orElseThrow(() -> new NoSuchElementException(
                                "Definition not found: " + key + " version " + version));
            } catch (NumberFormatException ignored) {
                // process keys rarely contain ':version' — fall through to latest-by-key
            }
        }
        return definitions.findLatestByKey(ref)
                .orElseThrow(() -> new NoSuchElementException("Definition not found: " + ref));
    }

    /**
     * Business key resolution (plan 30 В§4, old BpmExecutionService parity):
     * when start form declares {@code businessKeyVar} and input contains it в†’ use that value;
     * otherwise fall back to request {@code businessKey}.
     */
    static String resolveBusinessKey(ProcessDefinition model, Map<String, Object> values, String requestBk) {
        Optional<FormDataSpec> formOpt = StartFormValidator.startForm(model);
        if (formOpt.isPresent()) {
            String bkVar = formOpt.get().businessKeyVar();
            if (bkVar != null && !bkVar.isBlank()) {
                if (values != null && values.containsKey(bkVar)) {
                    Object v = values.get(bkVar);
                    if (v != null && !String.valueOf(v).isBlank()) {
                        return String.valueOf(v);
                    }
                }
                throw new StartFormValidationException(List.of(
                        new StartFormValidationException.FieldError(bkVar, "required")));
            }
        }
        if (requestBk != null && !requestBk.isBlank()) {
            return requestBk;
        }
        return null;
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
        InstanceRecord instance = instances.findInstanceById(task.instanceId()).orElseThrow();
        ProcessDefinition model = registry.get(instance.definitionId());
        FlowNode node = model.node(task.nodeId()).orElseThrow();

        Map<String, Object> values = input == null ? Map.of() : input;
        if (node instanceof UserTaskNode user && user.formData().isPresent()) {
            values = StartFormValidator.validateAndCoerce(user.formData().get(), values);
        }

        String submittedJson;
        try {
            submittedJson = json.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize submitted_data", e);
        }

        variables.putAll(task.instanceId(), values);
        tasks.save(new UserTaskRecord(
                task.id(), task.instanceId(), task.tokenId(), task.nodeId(), task.name(),
                task.assignee(), task.candidateGroups(), task.candidateUsers(),
                task.dueDate(), task.priority(), task.formKey(), submittedJson, task.claimTime(),
                true, task.createdAt(), clock.now()));

        TokenRecord waiting = tokens.findTokenById(task.tokenId()).orElseThrow();
        if (waiting.status() != TokenStatus.WAITING) {
            throw new IllegalStateException("Token is not WAITING for task " + taskId);
        }
        engine.continueAfterUserTask(model, waiting, instance.businessKey());
        return getInstance(instance.id());
    }

    @Override
    public InstanceView claimTask(String taskId, String assignee) {
        if (assignee == null || assignee.isBlank()) {
            throw new IllegalArgumentException("assignee is required");
        }
        UserTaskRecord claimed = tasks.claim(taskId, assignee, clock.now())
                .orElseThrow(() -> new NoSuchElementException("Task not found or already completed: " + taskId));
        return getInstance(claimed.instanceId());
    }
}

