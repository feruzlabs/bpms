package com.bpms.server.service;

import com.bpms.spi.engine.RuntimeModels.JobRecord;
import com.bpms.spi.engine.RuntimeModels.JobStatus;
import com.bpms.spi.port.IncidentPort;
import com.bpms.spi.port.InstanceControlPort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TypedJobHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 27 / 29: persisted node-revisit cap trips LOOP_DETECTED + SUSPEND on async job path. */
class RunawayGuardDispatcherTest {

    @Test
    void nodeRevisitExceededSuspendsInstanceAndSkipsJob() {
        AtomicBoolean suspended = new AtomicBoolean(false);
        AtomicReference<String> seen = new AtomicReference<>();
        RecordingIncidents incidents = new RecordingIncidents();

        InstanceControlPort control = new InstanceControlPort() {
            @Override public String statusOf(String instanceId) { return "RUNNING"; }
            @Override public boolean isHalted(String instanceId) { return false; }
            @Override public List<String> instanceTree(String rootId) { return List.of(rootId); }
            @Override public void terminate(String instanceId, String user, String reason) {}
            @Override public void suspend(String instanceId, String user, String reason) {
                suspended.set(true);
            }
            @Override public void resume(String instanceId) {}
            @Override public int maxNodeRevisitCount(String instanceId) { return 1001; }
            @Override public int tokenStateCount(String instanceId) { return 0; }
            @Override public int subprocessDepth(String instanceId) { return 1; }
            @Override public int spawnCountUnderRoot(String rootId) { return 1; }
        };

        RunawayGuard guard = new RunawayGuard(control, incidents, 1000, 50, 1000, 100_000);
        TypedJobHandler handler = new TypedJobHandler() {
            @Override public String type() { return "SERVICE_TASK"; }
            @Override public void handle(JobRecord job) { seen.set("RAN"); }
        };
        JobDispatcher dispatcher = new JobDispatcher(
                List.of(handler), control, new FakeJobs(), guard);

        dispatcher.handle(job("j1", "loop-inst"));

        assertEquals(null, seen.get());
        assertTrue(suspended.get());
        assertEquals(1, incidents.raised.size());
        assertEquals("LOOP_DETECTED", incidents.raised.getFirst().type());
    }

    @Test
    void nodeRevisitUnderThresholdRunsJob() {
        AtomicReference<String> seen = new AtomicReference<>();
        InstanceControlPort control = new InstanceControlPort() {
            @Override public String statusOf(String instanceId) { return "RUNNING"; }
            @Override public boolean isHalted(String instanceId) { return false; }
            @Override public List<String> instanceTree(String rootId) { return List.of(rootId); }
            @Override public void terminate(String instanceId, String user, String reason) {}
            @Override public void suspend(String instanceId, String user, String reason) {}
            @Override public void resume(String instanceId) {}
            @Override public int maxNodeRevisitCount(String instanceId) { return 500; }
            @Override public int tokenStateCount(String instanceId) { return 0; }
            @Override public int subprocessDepth(String instanceId) { return 1; }
            @Override public int spawnCountUnderRoot(String rootId) { return 1; }
        };
        RunawayGuard guard = new RunawayGuard(control, IncidentPort.NOOP, 1000, 50, 1000, 100_000);
        TypedJobHandler handler = new TypedJobHandler() {
            @Override public String type() { return "SERVICE_TASK"; }
            @Override public void handle(JobRecord job) { seen.set("RAN"); }
        };
        JobDispatcher dispatcher = new JobDispatcher(
                List.of(handler), control, new FakeJobs(), guard);

        dispatcher.handle(job("j1", "ok-inst"));

        assertEquals("RAN", seen.get());
    }

    private static JobRecord job(String id, String instanceId) {
        return new JobRecord(id, instanceId, "t1", "SERVICE_TASK", "{}", JobStatus.PENDING, 0, Instant.now());
    }

    static final class FakeJobs implements JobRepositoryPort {
        @Override public JobRecord save(JobRecord job) { return job; }
        @Override public Optional<JobRecord> findJobById(String id) { return Optional.empty(); }
        @Override public List<JobRecord> findPendingByInstance(String instanceId) { return List.of(); }
    }

    static final class RecordingIncidents implements IncidentPort {
        final List<Raised> raised = new ArrayList<>();
        @Override public String raise(String instanceId, String tokenId, String tokenStateId,
                                      String type, String severity, String message) {
            raised.add(new Raised(instanceId, type, message));
            return "inc-" + raised.size();
        }
        record Raised(String instanceId, String type, String message) {}
    }
}
