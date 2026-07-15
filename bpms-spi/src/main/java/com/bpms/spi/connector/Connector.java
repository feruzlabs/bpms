package com.bpms.spi.connector;

public interface Connector {
    String id();

    ConnectorResult execute(ConnectorContext context);

    default ConnectorDescriptor describe() {
        return new ConnectorDescriptor(id(), "", java.util.List.of(), java.util.List.of());
    }
}