package com.bpms.engine.behavior;

import com.bpms.core.definition.EventDefinition;
import com.bpms.core.definition.IntermediateCatchEventNode;
import com.bpms.core.definition.MessageEventDef;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.SignalEventDef;
import com.bpms.core.definition.TimerEventDef;
import com.bpms.engine.TimerService;
import com.bpms.spi.engine.RuntimeModels.EventSubscriptionRecord;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code intermediateCatchEvent}: TIMER/MESSAGE/SIGNAL park the token WAITING behind an {@code
 * event_subscription} (plan 32 Phase 2/3) — resumed later by {@code TimerJobHandler} or {@code
 * ExecutionEngine.correlateMessage}/{@code broadcastSignal} (via {@code ExecutionEngine.continueAfterEvent}).
 * {@code none}/unsupported definitions are a pure pass-through (a bare label — nothing to wait for).
 */
public final class IntermediateCatchEventBehavior implements NodeBehavior<IntermediateCatchEventNode> {

    @Override
    public Class<IntermediateCatchEventNode> nodeType() {
        return IntermediateCatchEventNode.class;
    }

    @Override
    public NodeResult execute(IntermediateCatchEventNode event, ExecutionContext ctx) {
        EventDefinition def = event.eventDefinition().orElse(null);
        TokenRecord token = ctx.token();

        if (def instanceof TimerEventDef timer) {
            Instant runAt = TimerService.resolveRunAt(timer, ctx.clock().now(), ctx.vars(), ctx.expressions());
            String subId = UUID.randomUUID().toString();
            ctx.eventSubscriptions().save(new EventSubscriptionRecord(
                    subId, token.instanceId(), token.id(), "TIMER", null, event.id(), null, ctx.clock().now()));
            ctx.enqueueTimerJob(event.id(), subId, runAt);
            ctx.parkWaiting();
            return NodeResult.waiting();
        }
        if (def instanceof MessageEventDef message) {
            String name = message.messageName() != null ? message.messageName() : message.messageRef();
            ctx.eventSubscriptions().save(new EventSubscriptionRecord(
                    UUID.randomUUID().toString(), token.instanceId(), token.id(), "MESSAGE", name, event.id(),
                    null, ctx.clock().now()));
            ctx.parkWaiting();
            return NodeResult.waiting();
        }
        if (def instanceof SignalEventDef signal) {
            String name = signal.signalName() != null ? signal.signalName() : signal.signalRef();
            ctx.eventSubscriptions().save(new EventSubscriptionRecord(
                    UUID.randomUUID().toString(), token.instanceId(), token.id(), "SIGNAL", name, event.id(),
                    null, ctx.clock().now()));
            ctx.parkWaiting();
            return NodeResult.waiting();
        }

        List<SequenceFlow> outgoing = ctx.definition().outgoing(event.id());
        return NodeResult.takeFlows(outgoing);
    }
}
