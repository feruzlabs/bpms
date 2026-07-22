package com.bpms.spi.port;

import com.bpms.spi.engine.RuntimeModels.EventSubscriptionRecord;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@code event_subscription} rows — TIMER/MESSAGE/SIGNAL waits parked by an intermediate
 * catch event, a {@code receiveTask}, or a boundary event (plan 32 Phases 2/3). Kept separate from
 * {@link TokenRepositoryPort}/{@link JobRepositoryPort} because it has its own lifecycle: created when a
 * token starts waiting, deleted the moment it fires (single-fire semantics) or when the activity/instance
 * it belongs to finishes/cancels first.
 */
public interface EventSubscriptionPort {

    EventSubscriptionRecord save(EventSubscriptionRecord subscription);

    void deleteById(String id);

    void deleteByInstanceId(String instanceId);

    /** Clears every subscription recorded against {@code nodeId} in this instance (plain catch's own id, or a boundary's attached activity id). */
    void deleteByInstanceAndNode(String instanceId, String nodeId);

    Optional<EventSubscriptionRecord> findById(String id);

    /** Named distinctly from {@link TokenRepositoryPort#findByInstanceId} so one adapter can implement both ports. */
    List<EventSubscriptionRecord> findSubscriptionsByInstanceId(String instanceId);

    /** Open (RUNNING/WAITING instance) subscriptions matching {@code type}+{@code eventName} — used to correlate a message/signal. */
    List<EventSubscriptionRecord> findOpenByTypeAndName(String type, String eventName);

    /** No-op default so {@code ExecutionEngine} tests that don't care about timer/message/signal events keep working. */
    EventSubscriptionPort NOOP = new EventSubscriptionPort() {
        @Override
        public EventSubscriptionRecord save(EventSubscriptionRecord subscription) {
            return subscription;
        }

        @Override
        public void deleteById(String id) {
        }

        @Override
        public void deleteByInstanceId(String instanceId) {
        }

        @Override
        public void deleteByInstanceAndNode(String instanceId, String nodeId) {
        }

        @Override
        public Optional<EventSubscriptionRecord> findById(String id) {
            return Optional.empty();
        }

        @Override
        public List<EventSubscriptionRecord> findSubscriptionsByInstanceId(String instanceId) {
            return List.of();
        }

        @Override
        public List<EventSubscriptionRecord> findOpenByTypeAndName(String type, String eventName) {
            return List.of();
        }
    };
}
