package com.bpms.engine.behavior;

import com.bpms.core.definition.MessageDef;
import com.bpms.core.definition.ReceiveTaskNode;
import com.bpms.spi.engine.RuntimeModels.EventSubscriptionRecord;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;

import java.util.UUID;

/**
 * {@code receiveTask} — the task-shaped form of an intermediate message catch: parks the token WAITING
 * behind a MESSAGE {@code event_subscription}, resumed by {@code ExecutionEngine.correlateMessage} (plan
 * 32 Phase 3).
 */
public final class ReceiveTaskBehavior implements NodeBehavior<ReceiveTaskNode> {

    @Override
    public Class<ReceiveTaskNode> nodeType() {
        return ReceiveTaskNode.class;
    }

    @Override
    public NodeResult execute(ReceiveTaskNode task, ExecutionContext ctx) {
        String name = resolveMessageName(task, ctx);
        TokenRecord token = ctx.token();
        ctx.eventSubscriptions().save(new EventSubscriptionRecord(
                UUID.randomUUID().toString(), token.instanceId(), token.id(), "MESSAGE", name, task.id(),
                null, ctx.clock().now()));
        ctx.parkWaiting();
        return NodeResult.waiting();
    }

    /** Resolves the message's business name from the definition's {@code messages()} table, falling back to the raw ref. */
    private static String resolveMessageName(ReceiveTaskNode task, ExecutionContext ctx) {
        String ref = task.messageRef();
        if (ref == null) {
            return null;
        }
        return ctx.definition().messages().stream()
                .filter(m -> ref.equals(m.id()))
                .map(MessageDef::name)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(ref);
    }
}
