package com.bpms.parser.camunda;

import com.bpms.core.compat.CompatWarning;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ParseContext {
    private final List<CompatWarning> warnings;
    private final Map<String, Object> metadata = new HashMap<>();

    ParseContext(List<CompatWarning> warnings) {
        this.warnings = warnings;
    }

    void warn(String elementId, String type, String reason) {
        warnings.add(new CompatWarning(elementId, type, reason));
    }

    void putMeta(String key, Object value) {
        metadata.put(key, value);
    }

    Map<String, Object> metadata() {
        return metadata;
    }
}