package com.bpms.spi.port;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import java.util.*;
public interface TokenRepositoryPort {
    TokenRecord save(TokenRecord token);
    List<TokenRecord> findByInstanceId(String instanceId);
    Optional<TokenRecord> findTokenById(String id);
}
