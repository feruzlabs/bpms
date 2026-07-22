package com.bpms.engine.behavior;

import com.bpms.core.definition.BoundaryEventNode;
import com.bpms.core.definition.ErrorEventDef;
import com.bpms.core.definition.EventDefinition;
import com.bpms.core.definition.MessageEventDef;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.SignalEventDef;
import com.bpms.core.definition.TimerEventDef;
import com.bpms.engine.TimerService;
import com.bpms.spi.engine.RuntimeModels.EventSubscriptionRecord;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared boundary-event wiring for activity behaviors ({@code ServiceTaskBehavior}, {@code
 * UserTaskBehavior}) — plan 32 Phase 3. Kept as one place so timer/message/signal boundary registration
 * (and error-boundary lookup) isn't copy-pasted into every activity that can carry a boundary event.
 *
 * <p><b>Subscription shape for a boundary</b> (see {@code EventSubscriptionRecord} javadoc): {@code
 * tokenId} = the activity's own token (still parked there), {@code nodeId} = the ATTACHED ACTIVITY's id
 * (so {@code deleteByInstanceAndNode(instanceId, activityId)} clears every boundary subscription for that
 * activity in one call when it finishes normally), and {@code configJson} carries the boundary event's OWN
 * node id (needed by {@code ExecutionEngine.fireBoundaryEvent} to find its outgoing flows) plus its {@code
 * cancelActivity} flag as {@code interrupting}.
 */
public final class BoundarySupport {

    private BoundarySupport() {
    }

    /** Registers a TIMER job / MESSAGE / SIGNAL subscription for every boundary event attached to {@code activityId}. Call on activity enter. */
    public static void registerBoundaryEvents(ExecutionContext ctx, String activityId) {
        for (BoundaryEventNode boundary : boundariesFor(ctx.definition(), activityId)) {
            EventDefinition def = boundary.eventDefinition().orElse(null);
            if (def instanceof TimerEventDef timer) {
                registerTimerBoundary(ctx, activityId, boundary, timer);
            } else if (def instanceof MessageEventDef message) {
                String name = message.messageName() != null ? message.messageName() : message.messageRef();
                registerWaitingBoundary(ctx, activityId, boundary, "MESSAGE", name);
            } else if (def instanceof SignalEventDef signal) {
                String name = signal.signalName() != null ? signal.signalName() : signal.signalRef();
                registerWaitingBoundary(ctx, activityId, boundary, "SIGNAL", name);
            }
            // ErrorEventDef boundaries are not a subscription — checked synchronously via findErrorBoundary
            // at the moment the activity's own action fails (see ServiceTaskBehavior).
        }
    }

    private static void registerTimerBoundary(
            ExecutionContext ctx, String activityId, BoundaryEventNode boundary, TimerEventDef timer
    ) {
        TokenRecord token = ctx.token();
        Instant runAt = TimerService.resolveRunAt(timer, ctx.clock().now(), ctx.vars(), ctx.expressions());
        String subId = UUID.randomUUID().toString();
        ctx.eventSubscriptions().save(new EventSubscriptionRecord(
                subId, token.instanceId(), token.id(), "TIMER", null, activityId,
                configJson(ctx, boundary), ctx.clock().now()));
        ctx.enqueueTimerJob(activityId, subId, runAt);
    }

    private static void registerWaitingBoundary(
            ExecutionContext ctx, String activityId, BoundaryEventNode boundary, String type, String eventName
    ) {
        TokenRecord token = ctx.token();
        ctx.eventSubscriptions().save(new EventSubscriptionRecord(
                UUID.randomUUID().toString(), token.instanceId(), token.id(), type, eventName, activityId,
                configJson(ctx, boundary), ctx.clock().now()));
    }

    private static String configJson(ExecutionContext ctx, BoundaryEventNode boundary) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("boundary", true);
        config.put("boundaryNodeId", boundary.id());
        config.put("interrupting", boundary.cancelActivity());
        try {
            return ctx.json().writeValueAsString(config);
        } catch (Exception e) {
            return null;
        }
    }

    /** Clears every boundary subscription (timer/message/signal) registered against {@code activityId} — call when the activity finishes normally. */
    public static void clearBoundaryEvents(ExecutionContext ctx, String activityId) {
        ctx.eventSubscriptions().deleteByInstanceAndNode(ctx.token().instanceId(), activityId);
    }

    /** The first {@code errorEventDefinition} boundary attached to {@code activityId}, if any (plan 32 Phase 3). */
    public static Optional<BoundaryEventNode> findErrorBoundary(ProcessDefinition definition, String activityId) {
        return boundariesFor(definition, activityId).stream()
                .filter(b -> b.eventDefinition().filter(ErrorEventDef.class::isInstance).isPresent())
                .findFirst();
    }

    /** Reroutes the CURRENT token onto the boundary's own outgoing flows — same-token synchronous error handling, no new token needed. */
    public static NodeResult takeErrorBoundaryFlow(ExecutionContext ctx, BoundaryEventNode boundary) {
        List<SequenceFlow> outgoing = ctx.definition().outgoing(boundary.id());
        return NodeResult.takeFlows(outgoing);
    }

    private static List<BoundaryEventNode> boundariesFor(ProcessDefinition definition, String activityId) {
        return definition.allNodes().stream()
                .filter(BoundaryEventNode.class::isInstance)
                .map(BoundaryEventNode.class::cast)
                .filter(b -> activityId.equals(b.attachedToRef()))
                .toList();
    }
}
