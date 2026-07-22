package com.bpms.server.service;

import com.bpms.engine.ConnectorRegistry;
import com.bpms.engine.ExecutionEngine;
import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.parser.camunda.CamundaCompatParser;
import com.bpms.spi.engine.RuntimeModels.DefinitionRecord;
import com.bpms.spi.engine.RuntimeModels.DefinitionVersionView;
import com.bpms.spi.engine.RuntimeModels.DeployResult;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;
import com.bpms.spi.port.DefinitionRegistry;
import com.bpms.spi.port.DefinitionRepositoryPort;
import com.bpms.spi.port.ExecutionLogPort;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.VariableStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 35: identical BPMN redeploy reuses version; content change creates v2 and flips is_latest.
 */
class ProcessVersioningDeployTest {

    private InMemoryDefs defs;
    private ProcessEngineService service;

    @BeforeEach
    void setUp() {
        defs = new InMemoryDefs();
        DefinitionRegistry registry = id -> {
            DefinitionRecord rec = defs.findDefinitionById(id).orElseThrow();
            return new CamundaCompatParser()
                    .parse(rec.bpmnXml().getBytes(StandardCharsets.UTF_8))
                    .definition();
        };
        ConnectorRegistry connectors = new ConnectorRegistry(List.of());
        ExecutionEngine engine = new ExecutionEngine(
                connectors, new SpelExpressionEvaluator(),
                new NoopInstances(), new NoopTokens(), new NoopVars(), new NoopTasks(), new NoopJobs(),
                job -> {}, Instant::now, false, new ObjectMapper());
        service = new ProcessEngineService(
                new CamundaCompatParser(), registry, defs,
                new NoopInstances(), new NoopTokens(), new NoopVars(), new NoopTasks(), new NoopJobs(),
                job -> {}, engine, Instant::now, new NoopLog(), new ObjectMapper(), true);
    }

    @Test
    void identicalBpmnTwice_reusesVersion_changedFalse() {
        byte[] bpmn = bpmn("EndOk");
        DeployResult first = service.deploy(bpmn);
        assertTrue(first.changed());
        assertEquals(1, first.version());
        assertEquals(1, defs.byId.size());

        DeployResult second = service.deploy(bpmn);
        assertFalse(second.changed());
        assertEquals(first.definitionId(), second.definitionId());
        assertEquals(1, second.version());
        assertEquals(first.checksum(), second.checksum());
        assertEquals(1, defs.byId.size(), "no second row when checksum matches");
    }

    @Test
    void changedBpmn_createsV2_andFlipsLatest() {
        DeployResult v1 = service.deploy(bpmn("EndOk"));
        DeployResult v2 = service.deploy(bpmn("EndAlert"));
        assertTrue(v2.changed());
        assertEquals(2, v2.version());
        assertEquals(2, defs.byId.size());

        DefinitionRecord old = defs.findDefinitionById(v1.definitionId()).orElseThrow();
        DefinitionRecord neu = defs.findDefinitionById(v2.definitionId()).orElseThrow();
        assertFalse(old.isLatest());
        assertTrue(neu.isLatest());

        List<DefinitionVersionView> catalog = defs.findVersionsByKey("exchange_rate_alert");
        assertEquals(2, catalog.size());
        assertEquals(1, catalog.get(0).version());
        assertFalse(catalog.get(0).isLatest());
        assertEquals(2, catalog.get(1).version());
        assertTrue(catalog.get(1).isLatest());
    }

    @Test
    void startRef_keyColonVersion_resolvesExact() {
        DeployResult v1 = service.deploy(bpmn("EndOk"));
        service.deploy(bpmn("EndAlert"));
        DefinitionRecord resolved = service.resolveDefinitionRef("exchange_rate_alert:1");
        assertEquals(v1.definitionId(), resolved.id());
        assertEquals(1, resolved.version());

        DefinitionRecord latest = service.resolveDefinitionRef("exchange_rate_alert");
        assertEquals(2, latest.version());
        assertTrue(latest.isLatest());
    }

    private static byte[] bpmn(String endId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                  id="Defs" targetNamespace="t">
                  <bpmn:process id="exchange_rate_alert" name="Exchange rate alert" isExecutable="true">
                    <bpmn:startEvent id="start"/>
                    <bpmn:endEvent id="%s"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="%s"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(endId, endId).getBytes(StandardCharsets.UTF_8);
    }

    static final class InMemoryDefs implements DefinitionRepositoryPort {
        final Map<String, DefinitionRecord> byId = new ConcurrentHashMap<>();

        @Override
        public DefinitionRecord save(DefinitionRecord definition) {
            byId.values().stream()
                    .filter(d -> d.key().equals(definition.key()) && d.isLatest())
                    .toList()
                    .forEach(d -> byId.put(d.id(), new DefinitionRecord(
                            d.id(), d.key(), d.name(), d.version(), d.sourceFormat(), d.bpmnXml(),
                            d.createdAt(), d.checksum(), false)));
            byId.put(definition.id(), definition);
            return definition;
        }

        @Override
        public Optional<DefinitionRecord> findDefinitionById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<DefinitionRecord> findLatestByKey(String key) {
            return byId.values().stream().filter(d -> d.key().equals(key) && d.isLatest()).findFirst();
        }

        @Override
        public Optional<DefinitionRecord> findByKeyAndVersion(String key, int version) {
            return byId.values().stream()
                    .filter(d -> d.key().equals(key) && d.version() == version)
                    .findFirst();
        }

        @Override
        public List<DefinitionRecord> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public List<DefinitionVersionView> findVersionsByKey(String key) {
            return byId.values().stream()
                    .filter(d -> d.key().equals(key))
                    .sorted(Comparator.comparingInt(DefinitionRecord::version))
                    .map(d -> new DefinitionVersionView(
                            d.id(), d.key(), d.name(), d.version(), d.isLatest(), d.checksum(),
                            d.createdAt(), "ACTIVE", 0L))
                    .toList();
        }

        @Override
        public int nextVersion(String key) {
            return byId.values().stream()
                    .filter(d -> d.key().equals(key))
                    .mapToInt(DefinitionRecord::version)
                    .max()
                    .orElse(0) + 1;
        }
    }

    static final class NoopInstances implements InstanceRepositoryPort {
        @Override public InstanceRecord save(InstanceRecord instance) { return instance; }
        @Override public Optional<InstanceRecord> findInstanceById(String id) { return Optional.empty(); }
    }

    static final class NoopTokens implements TokenRepositoryPort {
        @Override public TokenRecord save(TokenRecord token) { return token; }
        @Override public List<TokenRecord> findByInstanceId(String instanceId) { return List.of(); }
        @Override public Optional<TokenRecord> findTokenById(String id) { return Optional.empty(); }
    }

    static final class NoopVars implements VariableStorePort {
        @Override public void putAll(String instanceId, Map<String, Object> values) {}
        @Override public Map<String, Object> getAll(String instanceId) { return new HashMap<>(); }
    }

    static final class NoopTasks implements TaskRepositoryPort {
        @Override public UserTaskRecord save(UserTaskRecord task) { return task; }
        @Override public Optional<UserTaskRecord> findTaskById(String id) { return Optional.empty(); }
    }

    static final class NoopJobs implements JobRepositoryPort {
        @Override public JobRecord save(JobRecord job) { return job; }
        @Override public Optional<JobRecord> findJobById(String id) { return Optional.empty(); }
        @Override public List<JobRecord> findPendingByInstance(String instanceId) { return List.of(); }
    }

    static final class NoopLog implements ExecutionLogPort {
        @Override public void log(LogEntry entry) {}
        @Override public List<LogEntry> byInstance(String instanceId) { return List.of(); }
    }
}
