package com.bpms.spi.port;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;
import java.util.Optional;
public interface TaskRepositoryPort {
    UserTaskRecord save(UserTaskRecord task);
    Optional<UserTaskRecord> findTaskById(String id);
}
