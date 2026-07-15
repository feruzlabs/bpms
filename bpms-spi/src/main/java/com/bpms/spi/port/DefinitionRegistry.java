package com.bpms.spi.port;

import com.bpms.core.definition.ProcessDefinition;

/**
 * In-memory deployment cache: BPMN XML is canonical; parsed model lives in heap.
 * Definitions are immutable + versioned (new version ⇒ new id) so no invalidation is required.
 */
public interface DefinitionRegistry {

    /** Cache hit, or parse-once from DB XML on miss. */
    ProcessDefinition get(String definitionId);

    /** Optional warm after deploy so the first start does not re-parse. */
    default void warm(String definitionId, ProcessDefinition definition) {
        // no-op
    }
}
