package com.bpms.engine.behavior;

import com.bpms.core.definition.EndEventNode;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.core.definition.SubProcessNode;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * endEvent: normal close, or {@code terminateEndEvent} cancels every live token in the instance
 * (plan 31) — migrated verbatim from the old-engine inline block.
 *
 * <p>Plan 34 Phase 1: an end event that belongs to a {@code subProcess} (embedded, non-terminate) does
 * NOT finish the whole instance — it routes the token back out to the subprocess's own outgoing flows.
 * A multi-instance branch (tagged via {@code TokenRecord.parentMultiInstanceId}) is gated behind a
 * {@code _mi_join_<subId>} counter so the outer flow is taken exactly once, after every parallel branch
 * spawned by {@link SubProcessBehavior} has arrived.
 */
public final class EndEventBehavior implements NodeBehavior<EndEventNode> {

    @Override
    public Class<EndEventNode> nodeType() {
        return EndEventNode.class;
    }

    @Override
    public NodeResult execute(EndEventNode end, ExecutionContext ctx) {
        if (end.isTerminate()) {
            ctx.completeNodeState();
            ctx.terminateInstance();
            return NodeResult.finished();
        }

        Optional<SubProcessNode> containing = ctx.definition().containingSubProcess(end.id());
        if (containing.isEmpty()) {
            ctx.completeNodeState();
            ctx.close();
            return NodeResult.finished();
        }
        return exitSubProcess(containing.get(), ctx);
    }

    /**
     * Routes the token out of {@code sub} to its own outgoing flows instead of closing the instance.
     * Does NOT call {@code ctx.completeNodeState()} itself when returning {@code TakeFlows} — the engine's
     * generic {@code TakeFlows} handling in {@code ExecutionEngine.run()} does that (avoids double-firing
     * AFTER listeners / closing the token_state row twice).
     */
    private NodeResult exitSubProcess(SubProcessNode sub, ExecutionContext ctx) {
        TokenRecord at = ctx.token();
        String miId = at.parentMultiInstanceId();
        boolean isMiBranch = miId != null && miId.equals(sub.id()) && sub.multiInstance().isPresent();

        if (isMiBranch) {
            String joinKey = "_mi_join_" + sub.id();
            String totalKey = "_mi_total_" + sub.id();
            Map<String, Object> vars = ctx.vars();
            int total = ((Number) vars.getOrDefault(totalKey, 1)).intValue();
            int arrived = ((Number) vars.getOrDefault(joinKey, 0)).intValue() + 1;
            ctx.variables().putAll(at.instanceId(), Map.of(joinKey, arrived));
            ctx.tokens().save(new TokenRecord(at.id(), at.instanceId(), at.currentNodeId(), TokenStatus.COMPLETED, miId));
            if (arrived < total) {
                // This branch is done; other siblings haven't arrived yet — stop here, no outer advance.
                ctx.completeNodeState();
                return NodeResult.waiting();
            }
            ctx.variables().putAll(at.instanceId(), Map.of(joinKey, 0));
        }

        List<SequenceFlow> outer = ctx.definition().outgoing(sub.id());
        return NodeResult.takeFlows(outer);
    }
}
