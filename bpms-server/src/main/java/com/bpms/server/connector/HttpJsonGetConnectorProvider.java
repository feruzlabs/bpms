package com.bpms.server.connector;

import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Registers the reusable {@link HttpJsonGetConnector} ({@code http-json-get}) — plan 34.
 */
@Component
public class HttpJsonGetConnectorProvider implements ConnectorProvider {

    private final ObjectMapper json;

    public HttpJsonGetConnectorProvider(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Collection<Connector> connectors() {
        return List.of(new HttpJsonGetConnector(json));
    }
}
