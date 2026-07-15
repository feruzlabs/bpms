package com.bpms.connectors.creditconveyer;

import com.bpms.connectors.creditconveyer.auth.BpmsBasicAuthProvider;
import com.bpms.connectors.creditconveyer.connector.GetScoringResultV9Connector;
import com.bpms.connectors.creditconveyer.http.ConveyorClientV9;
import com.bpms.connectors.creditconveyer.service.GetScoreResultV9Service;
import com.bpms.core.definition.ProcessDefinition;
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
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.VariableStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stub PHP score endpoint → deploy/parse smoke BPMN → sync engine run → approved COMPLETED.
 */
class CreditV9SmokeWireMockTest {

    private WireMockServer wireMock;

    @BeforeEach
    void startStub() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlEqualTo("/v9/mobile/request/score/T1"))
                .withHeader("Authorization", equalTo("Basic YnBtczpicG1z")) // bpms:bpms
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "loans":[{"percentage":24.0,"amount":"5000000","month":12,"status":"ok","message":"m"}],
                                  "client_monthly_income": 2500000.0,
                                  "message": "approved by stub",
                                  "status": "APPROVED"
                                }
                                """)));
    }

    @AfterEach
    void stopStub() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    void smoke_deploy_start_reachesApproved() throws Exception {
        String endpoint = "http://localhost:" + wireMock.port();
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        Gson gson = new Gson();
        ObjectMapper json = new ObjectMapper();
        ConveyorClientV9 client = new ConveyorClientV9(
                http, gson, json, new BpmsBasicAuthProvider("bpms", "bpms"));
        GetScoreResultV9Service scoreSvc = new GetScoreResultV9Service(endpoint, client);
        GetScoringResultV9Connector connector = new GetScoringResultV9Connector(scoreSvc, gson);

        ConnectorRegistry connectors = new ConnectorRegistry(List.of(
                (ConnectorProvider) () -> List.of(connector)));

        byte[] bpmn = getClass().getResourceAsStream("/credit-v9-smoke.bpmn").readAllBytes();
        ProcessDefinition model = new CamundaCompatParser().parse(bpmn).definition();

        InMemoryPorts ports = new InMemoryPorts();
        ports.variables.put("token", "T1");
        ExecutionEngine engine = new ExecutionEngine(
                connectors, new SpelExpressionEvaluator(), ports, ports, ports, ports, ports,
                job -> {}, Instant::now, false, json);

        String iid = "i1";
        ports.instances.put(iid, new InstanceRecord(iid, "d1", "bk", InstanceStatus.RUNNING, Instant.now(), null));
        StartEventNode start = model.nodes().stream()
                .filter(StartEventNode.class::isInstance)
                .map(StartEventNode.class::cast)
                .findFirst()
                .orElseThrow();
        TokenRecord token = new TokenRecord(UUID.randomUUID().toString(), iid, start.id(), TokenStatus.ACTIVE, null);
        ports.tokens.put(token.id(), token);

        engine.run(model, token, "bk");

        assertEquals(InstanceStatus.COMPLETED, ports.instances.get(iid).status());
        assertTrue(ports.tokens.values().stream()
                .anyMatch(t -> "approved".equals(t.currentNodeId()) && t.status() == TokenStatus.COMPLETED));
        assertEquals(Boolean.TRUE, ports.variables.get("isOk"));
        assertEquals("APPROVED", ports.variables.get("clientStatus"));
        assertEquals(2_500_000L, ports.variables.get("avgIncome"));
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
    }
}
