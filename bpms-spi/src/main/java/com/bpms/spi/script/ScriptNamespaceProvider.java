package com.bpms.spi.script;

/**
 * Domain plugs script helpers into the expression context.
 * Core never hardcodes domain namespaces (hrms/labour/...).
 */
public interface ScriptNamespaceProvider {
    /** Root variable name in SpEL, e.g. \"bpms\" or \"credit\". */
    String namespace();

    /** Bean / helper exposed under {@link #namespace()}. */
    Object helper();
}