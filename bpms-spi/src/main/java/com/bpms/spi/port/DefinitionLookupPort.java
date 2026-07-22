package com.bpms.spi.port;

import com.bpms.core.definition.ProcessDefinition;

import java.util.Optional;

/**
 * Process-key/id → parsed {@link ProcessDefinition} lookup for the engine (plan 34 Phase 1 — callActivity).
 * Kept as its own SPI port (rather than exposing {@code DefinitionRegistry} directly to {@code bpms-engine})
 * so the engine module doesn't need to depend on the repository/parser wiring — {@code EngineConfig} adapts
 * {@code DefinitionRepositoryPort} + {@code DefinitionRegistry} into this narrow interface.
 */
public interface DefinitionLookupPort {

    /** The latest deployed {@link ProcessDefinition} for a {@code calledElement}/process key, parsed and cached. */
    Optional<ProcessDefinition> findDefinitionByKey(String processKey);

    /** The latest deployed definition's DB id for a process key — used as {@code InstanceRecord.definitionId()}. */
    Optional<String> findDefinitionIdByKey(String processKey);

    /**
     * The parsed {@link ProcessDefinition} for a definition DB id (as stored on {@code InstanceRecord.definitionId()}).
     * Needed to resume a parent instance waiting on a callActivity once its child completes — the engine only
     * has the parent's {@code definitionId}, not its process key.
     */
    Optional<ProcessDefinition> findDefinitionById(String definitionId);

    /** No deployed definitions reachable — used as the default when the engine is built without this port wired (tests). */
    DefinitionLookupPort EMPTY = new DefinitionLookupPort() {
        @Override
        public Optional<ProcessDefinition> findDefinitionByKey(String processKey) {
            return Optional.empty();
        }

        @Override
        public Optional<String> findDefinitionIdByKey(String processKey) {
            return Optional.empty();
        }

        @Override
        public Optional<ProcessDefinition> findDefinitionById(String definitionId) {
            return Optional.empty();
        }
    };
}
