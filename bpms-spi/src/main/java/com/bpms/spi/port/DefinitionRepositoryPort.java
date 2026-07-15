package com.bpms.spi.port;
import com.bpms.spi.engine.RuntimeModels.DefinitionRecord;
import java.util.*;
public interface DefinitionRepositoryPort {
    DefinitionRecord save(DefinitionRecord definition);
    Optional<DefinitionRecord> findDefinitionById(String id);
    Optional<DefinitionRecord> findLatestByKey(String key);
    List<DefinitionRecord> findAll();
    int nextVersion(String key);
}
