package com.bpms.connectors.creditconveyer.connector.support;

import java.util.Map;

/** SPI input/output helpers — *VarSet values are output variable NAMES. */
public final class Io {
    private Io() {}

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** Output variable name from a *VarSet input (literal string). */
    public static String name(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static void put(Map<String, Object> m, String name, Object v) {
        if (name != null && !name.isBlank()) {
            m.put(name, v);
        }
    }
}
