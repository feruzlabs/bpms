package com.bpms.server.service;

import com.bpms.core.definition.ProcessDefinition;
import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.JobStatus;
import com.bpms.spi.port.InstanceControlPort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TypedJobHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        FakeJobs jobs = new FakeJobs();
        InstanceControlPort control = status("RUNNING");
        JobDispatcher dispatcher = new JobDispatcher(List.of(start, service), control, jobs, guard(control));
        dispatcher.handle(job("PROCESS_START"));
        assertEquals("START", seen.get());
        dispatcher.handle(job("SERVICE_TASK"));
        assertEquals("SERVICE", seen.get());
    }

    @Test
    void jobDispatcherRejectsUnknownType() {
        InstanceControlPort control = status("RUNNING");
        JobDispatcher dispatcher = new JobDispatcher(List.of(), control, new FakeJobs(), guard(control));
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
        InstanceControlPort control = status("RUNNING");
        assertThrows(IllegalStateException.class,
                () -> new JobDispatcher(List.of(a, b), control, new FakeJobs(), guard(control)));
    }

    @Test
    void jobDispatcherAckDropsTerminatedInstanceJob() {
        AtomicReference<String> seen = new AtomicReference<>();
        TypedJobHandler service = new TypedJobHandler() {
            @Override public String type() { return "SERVICE_TASK"; }
            @Override public void handle(JobRecord job) { seen.set("RAN"); }
        };
        FakeJobs jobs = new FakeJobs();
        InstanceControlPort control = status("TERMINATED");
        JobDispatcher dispatcher = new JobDispatcher(List.of(service), control, jobs, guard(control));

        dispatcher.handle(job("SERVICE_TASK"));

        assertEquals(null, seen.get()); // handler never ran
        assertEquals(1, jobs.saved.size());
        assertEquals(JobStatus.CANCELED, jobs.saved.getFirst().status()); // ack-and-drop
    }

    @Test
    void jobDispatcherSkipsSuspendedInstanceJobWithoutCanceling() {
        AtomicReference<String> seen = new AtomicReference<>();
        TypedJobHandler service = new TypedJobHandler() {
            @Override public String type() { return "SERVICE_TASK"; }
            @Override public void handle(JobRecord job) { seen.set("RAN"); }
        };
        FakeJobs jobs = new FakeJobs();
        InstanceControlPort control = status("SUSPENDED");
        JobDispatcher dispatcher = new JobDispatcher(List.of(service), control, jobs, guard(control));

        dispatcher.handle(job("SERVICE_TASK"));

        assertEquals(null, seen.get());        // handler never ran
        assertTrue(jobs.saved.isEmpty());      // left PENDING for resume, not canceled
    }

    private static InstanceControlPort status(String status) {
        return new InstanceControlPort() {
            @Override public String statusOf(String instanceId) { return status; }
            @Override public boolean isHalted(String instanceId) {
                return "TERMINATED".equals(status) || "SUSPENDED".equals(status);
            }
            @Override public List<String> instanceTree(String rootId) { return List.of(rootId); }
            @Override public void terminate(String instanceId, String user, String reason) {}
            @Override public void suspend(String instanceId, String user, String reason) {}
            @Override public void resume(String instanceId) {}
            @Override public int maxNodeRevisitCount(String instanceId) { return 0; }
            @Override public int tokenStateCount(String instanceId) { return 0; }
            @Override public int subprocessDepth(String instanceId) { return 1; }
            @Override public int spawnCountUnderRoot(String rootId) { return 1; }
        };
    }

    /** Guard with huge caps → never trips on these small in-memory scenarios. */
    private static RunawayGuard guard(InstanceControlPort control) {
        return new RunawayGuard(control, com.bpms.spi.port.IncidentPort.NOOP,
                1_000_000, 1_000_000, 1_000_000, 1_000_000);
    }

    static final class FakeJobs implements JobRepositoryPort {
        final List<JobRecord> saved = new ArrayList<>();
        @Override public JobRecord save(JobRecord job) { saved.add(job); return job; }
        @Override public Optional<JobRecord> findJobById(String id) { return Optional.empty(); }
        @Override public List<JobRecord> findPendingByInstance(String instanceId) { return List.of(); }
    }

    private static JobRecord job(String type) {
        return new JobRecord("j1", "i1", "t1", type, "{}", JobStatus.PENDING, 0, Instant.now());
    }

    private static ProcessDefinition emptyModel() {
        return new ProcessDefinition(
                "p", "p", "p", List.of(), List.of(), List.of(), List.of(), Map.of());
    }
}
