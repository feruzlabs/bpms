package com.bpms.spi.connector;

import java.util.Map;

public record ConnectorContext(
        String businessKey,
        Map<String, Object> variables,
        Map<String, Object> inputs
) {
}