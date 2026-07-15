package com.bpms.spi.port;
import java.util.Map;
public interface VariableStorePort {
    void putAll(String instanceId, Map<String, Object> variables);
    Map<String, Object> getAll(String instanceId);
}
