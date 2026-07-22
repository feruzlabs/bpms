package com.bpms.spi.port;

import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;

import java.util.Optional;

public interface TaskRepositoryPort {
    UserTaskRecord save(UserTaskRecord task);

    Optional<UserTaskRecord> findTaskById(String id);

    /** Sets assignee + claim_time when the task is still open; returns empty if missing/completed. */
    default Optional<UserTaskRecord> claim(String taskId, String assignee, java.time.Instant claimTime) {
        Optional<UserTaskRecord> found = findTaskById(taskId);
        if (found.isEmpty() || found.get().completed()) {
            return Optional.empty();
        }
        UserTaskRecord t = found.get();
        return Optional.of(save(new UserTaskRecord(
                t.id(), t.instanceId(), t.tokenId(), t.nodeId(), t.name(),
                assignee, t.candidateGroups(), t.candidateUsers(), t.dueDate(), t.priority(),
                t.formKey(), t.submittedData(), claimTime, false, t.createdAt(), null)));
    }

    /** Marks all open user tasks for an instance completed (terminateEnd / admin stop). */
    default void completeOpenTasks(String instanceId, java.time.Instant at) {
        // optional — JPA adapter overrides
    }

    /**
     * Marks the single open user task tied to {@code tokenId} completed — used when an interrupting
     * boundary event fires while the activity token is parked at a {@code userTask} (plan 32 Phase 3).
     * Narrower than {@link #completeOpenTasks} (which closes every open task in the whole instance).
     */
    default void completeOpenTaskForToken(String tokenId, java.time.Instant at) {
        // optional — JPA adapter overrides; default no-op is safe when the activity wasn't a userTask.
    }
}
