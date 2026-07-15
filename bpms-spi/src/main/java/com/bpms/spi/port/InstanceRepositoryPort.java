package com.bpms.spi.port;
import com.bpms.spi.engine.RuntimeModels.InstanceRecord;
import java.util.Optional;
public interface InstanceRepositoryPort {
    InstanceRecord save(InstanceRecord instance);
    Optional<InstanceRecord> findInstanceById(String id);
}
