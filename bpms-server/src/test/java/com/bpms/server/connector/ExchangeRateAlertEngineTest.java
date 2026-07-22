package com.bpms.server.connector;

import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.ServiceTaskNode;
import com.bpms.core.definition.StartEventNode;
import com.bpms.engine.ConnectorRegistry;
import com.bpms.engine.ExecutionEngine;
import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.parser.camunda.CamundaCompatParser;
import com.bpms.spi.connector.ConnectorProvider;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;
import com.bpms.spi.port.ExecutionLogPort;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.VariableStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 34 end-to-end: start vars → http-json-get (stub HTTP) → exclusive gateway → EndAlert / EndOk.
 */
class ExchangeRateAlertEngineTest {

    private HttpServer server;
    private String apiBase;
    private final List<ExecutionLogPort.LogEntry> logs = new ArrayList<>();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v6/latest/USD", exchange -> {
            byte[] body = """
                    {"result":"success","rates":{"UZS":12600.0}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void exampleBpmnParsesWithHttpJsonGetConnector() throws Exception {
        Path bpmn = locateExample();
        ProcessDefinition def = new CamundaCompatParser().parse(Files.readAllBytes(bpmn)).definition();
        assertEquals("exchange_rate_alert", def.processId());
        StartEventNode start = (StartEventNode) def.node("start").orElseThrow();
        assertEquals("exchange_rate_form", start.formData().orElseThrow().formKey());
        assertEquals(3, start.formData().orElseThrow().fields().size());
        ServiceTaskNode fetch = (ServiceTaskNode) def.node("fetchRate").orElseThrow();
        var impl = (com.bpms.core.definition.ConnectorImplementation) fetch.implementation();
        assertEquals("http-json-get", impl.binding().connectorId());
    }

    private static Path locateExample() {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = {
                cwd.resolve("docs/examples/exchange_rate_alert.bpmn"),
                cwd.resolve("../docs/examples/exchange_rate_alert.bpmn"),
                cwd.getParent().resolve("docs/examples/exchange_rate_alert.bpmn")
        };
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c.normalize();
            }
        }
        throw new IllegalStateException("exchange_rate_alert.bpmn not found from " + cwd);
    }

    @Test
    void aboveThresholdReachesEndAlert() throws Exception {
        runScenario(12000, "EndAlert");
    }

    @Test
    void belowThresholdTakesDefaultEndOk() throws Exception {
        runScenario(20000, "EndOk");
    }

    @Test
    void httpFailureFailsInstance() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v6/latest/USD", exchange -> {
            byte[] body = "err".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort();

        InMemoryPorts ports = new InMemoryPorts();
        ExecutionEngine engine = newEngine(ports);
        ProcessDefinition def = parseStubBpmn();

        ports.instances.put("i1", new InstanceRecord("i1", def.id(), "bk", InstanceStatus.RUNNING, Instant.now(), null));
        ports.variables.putAll(Map.of("base", "USD", "target", "UZS", "threshold", 12000, "apiBase", apiBase));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        try {
            engine.run(def, token, "bk");
        } catch (RuntimeException ignored) {
            // connector fail throws after markFailed
        }

        assertEquals(InstanceStatus.FAILED, ports.instances.get("i1").status());
        assertTrue(logs.stream().anyMatch(e -> "CONNECTOR_ERROR".equals(e.eventType())));
    }

    private void runScenario(int threshold, String expectedEnd) throws Exception {
        InMemoryPorts ports = new InMemoryPorts();
        ExecutionEngine engine = newEngine(ports);
        ProcessDefinition def = parseStubBpmn();

        ports.instances.put("i1", new InstanceRecord("i1", def.id(), "bk", InstanceStatus.RUNNING, Instant.now(), null));
        ports.variables.putAll(Map.of(
                "base", "USD",
                "target", "UZS",
                "threshold", threshold,
                "apiBase", apiBase));
        TokenRecord token = new TokenRecord("t1", "i1", "start", TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        engine.run(def, token, "bk");

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get("i1").status());
        assertEquals(expectedEnd.equals("EndAlert"), Boolean.TRUE.equals(ports.variables.get("aboveThreshold")));
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> expectedEnd.equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));
        assertTrue(logs.stream().anyMatch(e -> "CONNECTOR_START".equals(e.eventType())));
        assertTrue(logs.stream().anyMatch(e -> "CONNECTOR_END".equals(e.eventType())));
        assertTrue(ports.variables.containsKey("rate") || ports.variables.containsKey("result"));
    }

    private ProcessDefinition parseStubBpmn() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                  id="Defs" targetNamespace="t">
                  <bpmn:process id="exchange_rate_alert" isExecutable="true">
                    <bpmn:startEvent id="start"/>
                    <bpmn:serviceTask id="fetchRate" name="Fetch">
                      <bpmn:extensionElements>
                        <camunda:connector>
                          <camunda:connectorId>http-json-get</camunda:connectorId>
                          <camunda:inputOutput>
                            <camunda:inputParameter name="url">${apiBase}/v6/latest/${base}</camunda:inputParameter>
                            <camunda:inputParameter name="resultPath">rates.${target}</camunda:inputParameter>
                            <camunda:inputParameter name="threshold">${threshold}</camunda:inputParameter>
                          </camunda:inputOutput>
                        </camunda:connector>
                      </bpmn:extensionElements>
                    </bpmn:serviceTask>
                    <bpmn:exclusiveGateway id="gw" default="f_ok"/>
                    <bpmn:endEvent id="EndAlert"/>
                    <bpmn:endEvent id="EndOk"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="fetchRate"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="fetchRate" targetRef="gw"/>
                    <bpmn:sequenceFlow id="f_alert" sourceRef="gw" targetRef="EndAlert">
                      <bpmn:conditionExpression>$aboveThreshold == true</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="f_ok" sourceRef="gw" targetRef="EndOk"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        return new CamundaCompatParser().parse(xml.getBytes(StandardCharsets.UTF_8)).definition();
    }

    private ExecutionEngine newEngine(InMemoryPorts ports) {
        HttpJsonGetConnector connector = new HttpJsonGetConnector(new ObjectMapper());
        ConnectorRegistry registry = new ConnectorRegistry(List.of((ConnectorProvider) () -> List.of(connector)));
        ExecutionLogPort execLog = new ExecutionLogPort() {
            @Override
            public void log(LogEntry entry) {
                logs.add(entry);
            }

            @Override
            public List<LogEntry> byInstance(String instanceId) {
                return logs.stream().filter(e -> instanceId.equals(e.instanceId())).toList();
            }
        };
        return new ExecutionEngine(
                registry, new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, new ObjectMapper(),
                execLog, null, null, com.bpms.spi.port.TerminationSignal.NEVER, null,
                ExecutionEngine.DEFAULT_MAX_STEPS_PER_RUN, ExecutionEngine.DEFAULT_MAX_NODE_REVISITS_PER_RUN,
                null, null, null);
    }

    static final class InMemoryPorts implements InstanceRepositoryPort, TokenRepositoryPort, VariableStorePort,
            TaskRepositoryPort, JobRepositoryPort {
        final Map<String, InstanceRecord> instances = new ConcurrentHashMap<>();
        final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();
        final Map<String, Object> variables = new ConcurrentHashMap<>();

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
        @Override public JobRecord save(JobRecord job) { return job; }
        @Override public Optional<JobRecord> findJobById(String id) { return Optional.empty(); }
        @Override public List<JobRecord> findPendingByInstance(String instanceId) { return List.of(); }
    }
}
