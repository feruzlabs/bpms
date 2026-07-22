package com.bpms.spi.port;

import com.bpms.spi.engine.RuntimeModels.DefinitionRecord;
import com.bpms.spi.engine.RuntimeModels.DefinitionVersionView;

import java.util.List;
import java.util.Optional;

public interface DefinitionRepositoryPort {
    DefinitionRecord save(DefinitionRecord definition);

    Optional<DefinitionRecord> findDefinitionById(String id);

    Optional<DefinitionRecord> findLatestByKey(String key);

    Optional<DefinitionRecord> findByKeyAndVersion(String key, int version);

    List<DefinitionRecord> findAll();

    /** All versions for a process key (ascending version), with {@code running_instances} counts (plan 35). */
    List<DefinitionVersionView> findVersionsByKey(String key);

    /** Next version number for {@code key} within the current tenant (plan 35 — tenant-scoped). */
    int nextVersion(String key);
}
