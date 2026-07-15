package com.bpms.engine;

import com.bpms.spi.connector.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectorRegistryTest {
    @Test void rejectsDuplicateConnectorIds() {
        Connector connector = new Connector() {
            public String id() { return "duplicate"; }
            public ConnectorResult execute(ConnectorContext context) { return ConnectorResult.ok(Map.of()); }
        };
        ConnectorProvider provider = () -> List.of(connector, connector);
        assertThrows(IllegalStateException.class, () -> new ConnectorRegistry(List.of(provider)));
    }
}
