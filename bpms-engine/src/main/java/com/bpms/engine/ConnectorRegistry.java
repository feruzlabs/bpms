package com.bpms.engine;

import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorProvider;
import java.util.*;

public final class ConnectorRegistry {
    private final Map<String, Connector> connectors;
    public ConnectorRegistry(List<ConnectorProvider> providers) {
        Map<String, Connector> collected = new HashMap<>();
        for (ConnectorProvider provider : providers) for (Connector connector : provider.connectors()) {
            if (collected.putIfAbsent(connector.id(), connector) != null) {
                throw new IllegalStateException("Duplicate connector id: " + connector.id());
            }
        }
        connectors = Map.copyOf(collected);
    }
    public Connector required(String id) {
        Connector connector = connectors.get(id);
        if (connector == null) throw new IllegalStateException("No connector registered for id: " + id);
        return connector;
    }
}
