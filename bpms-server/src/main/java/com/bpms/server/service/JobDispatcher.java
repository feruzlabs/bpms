package com.bpms.server.service;

import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.TypedJobHandler;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Single {@link JobQueuePort.JobHandler} bean — routes by {@link JobRecord#type()}. */
@Component
@Primary
public class JobDispatcher implements JobQueuePort.JobHandler {

    private final Map<String, TypedJobHandler> byType;

    public JobDispatcher(List<TypedJobHandler> handlers) {
        this.byType = handlers.stream()
                .collect(Collectors.toMap(TypedJobHandler::type, Function.identity(), (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate TypedJobHandler for type: " + a.type());
                }));
    }

    @Override
    public void handle(JobRecord job) {
        TypedJobHandler h = byType.get(job.type());
        if (h == null) {
            throw new IllegalStateException("No handler for job type: " + job.type());
        }
        h.handle(job);
    }
}
