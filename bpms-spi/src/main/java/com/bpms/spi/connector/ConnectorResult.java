package com.bpms.spi.connector;

import java.util.Map;

public record ConnectorResult(boolean success, Map<String, Object> outputs, String errorMessage) {
    public static ConnectorResult ok(Map<String, Object> outputs) {
        return new ConnectorResult(true, outputs, null);
    }

    public static ConnectorResult fail(String errorMessage) {
        return new ConnectorResult(false, Map.of(), errorMessage);
    }
}