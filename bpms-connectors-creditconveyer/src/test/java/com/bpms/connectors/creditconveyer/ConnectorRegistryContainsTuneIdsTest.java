package com.bpms.connectors.creditconveyer;

import com.bpms.engine.ConnectorRegistry;
import com.bpms.spi.connector.Connector;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses connectorIds from TUNE 4888/6004/7000 BPMN and asserts each creditConveyer id
 * is registered by {@link CreditConveyerConnectorProvider}.
 */
class ConnectorRegistryContainsTuneIdsTest {

    private static final Pattern CONNECTOR_ID = Pattern.compile("<camunda:connectorId>([^<]+)</camunda:connectorId>");

    private static final Path CORPUS = Path.of("..", "compat-corpus", "credit-conveyor");

    @Test
    void providerRegistersAllTune4888And6004And7000CreditConveyerIds() throws Exception {
        Set<String> bpmnIds = new HashSet<>();
        bpmnIds.addAll(parseIds(CORPUS.resolve("TUNE_CREDIT_REQUEST_4888.bpmn")));
        bpmnIds.addAll(parseIds(CORPUS.resolve("TUNE_CREDIT_REQUEST_6004.bpmn")));
        bpmnIds.addAll(parseIds(CORPUS.resolve("TUNE_CREDIT_REQUEST_7000.bpmn")));

        CreditConveyerConnectorProvider provider = new CreditConveyerConnectorProvider(
                // v9 (16)
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                // v8 (13)
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                // v6 (9)
                null, null, null, null, null, null, null, null, null,
                // BI
                null,
                new Gson());

        ConnectorRegistry registry = new ConnectorRegistry(java.util.List.of(provider));
        Set<String> registered = provider.connectors().stream().map(Connector::id).collect(Collectors.toSet());

        for (String id : bpmnIds) {
            assertTrue(registered.contains(id),
                    () -> "Missing connectorId from BPMN: " + id + " (registered=" + registered + ")");
            registry.required(id);
        }

        assertTrue(registered.size() >= 40,
                "Expected 16 v9 + 13 v8 + 10 v6/v7 + 1 BI = 40 connectors, got " + registered.size());
    }

    private static Set<String> parseIds(Path bpmn) throws Exception {
        String xml = Files.readString(bpmn);
        Set<String> ids = new HashSet<>();
        Matcher m = CONNECTOR_ID.matcher(xml);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }
}
