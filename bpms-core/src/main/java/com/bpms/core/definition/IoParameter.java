package com.bpms.core.definition;

import java.util.List;
import java.util.Map;

public record IoParameter(String name, String value, List<String> list, Map<String, String> map) {
    public static IoParameter scalar(String name, String value) {
        return new IoParameter(name, value, List.of(), Map.of());
    }
}