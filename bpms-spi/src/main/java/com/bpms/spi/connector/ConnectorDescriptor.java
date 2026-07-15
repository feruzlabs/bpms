package com.bpms.spi.connector;

import java.util.List;

public record ConnectorDescriptor(
        String id,
        String description,
        List<ConnectorInputDesc> inputs,
        List<String> outputs
) {
}