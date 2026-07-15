package com.bpms.core.definition;

import java.util.List;

public record ConnectorBinding(String connectorId, List<IoParameter> inputs, List<IoParameter> outputs) {
}