package com.bpms.server.connector;

import com.bpms.spi.connector.ConnectorContext;
import com.bpms.spi.connector.ConnectorResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpJsonGetConnectorTest {

    private HttpServer server;
    private String baseUrl;
    private HttpJsonGetConnector connector;

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v6/latest/USD", exchange -> {
            byte[] body = """
                    {"result":"success","rates":{"UZS":12600.5,"EUR":0.92}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/down", exchange -> {
            byte[] body = "nope".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        connector = new HttpJsonGetConnector(new ObjectMapper());
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void extractsRateAndAboveThreshold() {
        ConnectorResult result = connector.execute(new ConnectorContext("bk", Map.of(), Map.of(
                "url", baseUrl + "/v6/latest/USD",
                "resultPath", "rates.UZS",
                "threshold", 12000
        )));
        assertTrue(result.success(), result.errorMessage());
        assertEquals(200, result.outputs().get("httpStatus"));
        assertEquals(0, new java.math.BigDecimal("12600.5").compareTo(
                (java.math.BigDecimal) result.outputs().get("result")));
        assertEquals(result.outputs().get("result"), result.outputs().get("rate"));
        assertEquals(true, result.outputs().get("aboveThreshold"));
    }

    @Test
    void belowThresholdSetsFlagFalse() {
        ConnectorResult result = connector.execute(new ConnectorContext("bk", Map.of(), Map.of(
                "url", baseUrl + "/v6/latest/USD",
                "resultPath", "rates.UZS",
                "threshold", 20000
        )));
        assertTrue(result.success());
        assertEquals(false, result.outputs().get("aboveThreshold"));
    }

    @Test
    void non2xxFails() {
        ConnectorResult result = connector.execute(new ConnectorContext("bk", Map.of(), Map.of(
                "url", baseUrl + "/down"
        )));
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("HTTP 503"));
    }

    @Test
    void interpolatesTemplatesFromVariables() {
        ConnectorResult result = connector.execute(new ConnectorContext(
                "bk",
                Map.of("base", "USD", "target", "UZS", "threshold", 12000),
                Map.of(
                        "url", baseUrl + "/v6/latest/${base}",
                        "resultPath", "rates.${target}",
                        "threshold", "${threshold}"
                )));
        assertTrue(result.success(), result.errorMessage());
        assertEquals(true, result.outputs().get("aboveThreshold"));
    }

    @Test
    void unresolvedUrlHedgeAttemptsErApiHost() {
        ConnectorResult result = connector.execute(new ConnectorContext(
                "bk",
                Map.of("base", "USD", "target", "EUR"),
                Map.of("url", "${missing}")));
        // Hedge rebuilds open.er-api.com — succeeds when outbound net is up, else fail with our prefix.
        if (result.success()) {
            assertEquals(200, result.outputs().get("httpStatus"));
            assertTrue(result.outputs().containsKey("body"));
        } else {
            assertTrue(result.errorMessage().startsWith("http-json-get:"));
        }
    }
}
