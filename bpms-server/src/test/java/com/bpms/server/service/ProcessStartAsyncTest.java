package com.bpms.server.service;

import com.bpms.core.definition.ProcessDefinition;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.JobStatus;
import com.bpms.spi.port.TypedJobHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessStartAsyncTest {

    @Test
    void resolveBusinessKeyPrefersRequest() {
        ProcessDefinition model = emptyModel();
        String bk = ProcessEngineService.resolveBusinessKey(
                model,
                Map.of("request_id_tune_credit_request_start_form", "from-form"),
                "from-request");
        assertEquals("from-request", bk);
    }

    @Test
    void resolveBusinessKeyFallsBackToTuneRequestIdFormVar() {
        ProcessDefinition model = emptyModel();
        String bk = ProcessEngineService.resolveBusinessKey(
                model,
                Map.of("request_id_tune_credit_request_start_form", "REQ-99"),
                null);
        assertEquals("REQ-99", bk);
    }

    @Test
    void resolveBusinessKeyUsesMetadataVarName() {
        ProcessDefinition model = new ProcessDefinition(
                "p", "p", "p", List.of(), List.of(), List.of(), List.of(),
                Map.of("businessProcessKeyVar", "my_bk_field"));
        String bk = ProcessEngineService.resolveBusinessKey(
                model, Map.of("my_bk_field", "BK-1"), "  ");
        assertEquals("BK-1", bk);
    }

    @Test
    void jobDispatcherRoutesByType() {
        AtomicReference<String> seen = new AtomicReference<>();
        TypedJobHandler start = new TypedJobHandler() {
            @Override public String type() { return "PROCESS_START"; }
            @Override public void handle(JobRecord job) { seen.set("START"); }
        };
        TypedJobHandler service = new TypedJobHandler() {
            @Override public String type() { return "SERVICE_TASK"; }
            @Override public void handle(JobRecord job) { seen.set("SERVICE"); }
        };
        JobDispatcher dispatcher = new JobDispatcher(List.of(start, service));
        dispatcher.handle(job("PROCESS_START"));
        assertEquals("START", seen.get());
        dispatcher.handle(job("SERVICE_TASK"));
        assertEquals("SERVICE", seen.get());
    }

    @Test
    void jobDispatcherRejectsUnknownType() {
        JobDispatcher dispatcher = new JobDispatcher(List.of());
        assertThrows(IllegalStateException.class, () -> dispatcher.handle(job("UNKNOWN")));
    }

    @Test
    void jobDispatcherRejectsDuplicateTypes() {
        TypedJobHandler a = new TypedJobHandler() {
            @Override public String type() { return "SERVICE_TASK"; }
            @Override public void handle(JobRecord job) {}
        };
        TypedJobHandler b = new TypedJobHandler() {
            @Override public String type() { return "SERVICE_TASK"; }
            @Override public void handle(JobRecord job) {}
        };
        assertThrows(IllegalStateException.class, () -> new JobDispatcher(List.of(a, b)));
    }

    private static JobRecord job(String type) {
        return new JobRecord("j1", "i1", "t1", type, "{}", JobStatus.PENDING, 0, Instant.now());
    }

    private static ProcessDefinition emptyModel() {
        return new ProcessDefinition(
                "p", "p", "p", List.of(), List.of(), List.of(), List.of(), Map.of());
    }
}
