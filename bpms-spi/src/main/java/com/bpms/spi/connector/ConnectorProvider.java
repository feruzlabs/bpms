package com.bpms.spi.connector;

import java.util.Collection;

public interface ConnectorProvider {
    Collection<Connector> connectors();
}
