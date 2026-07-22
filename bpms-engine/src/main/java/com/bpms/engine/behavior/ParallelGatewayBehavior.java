package com.bpms.engine.behavior;

import com.bpms.core.definition.ParallelGatewayNode;
import com.bpms.core.definition.SequenceFlow;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * parallelGateway: join (wait for every incoming branch via an {@code _join_&lt;nodeId&gt;} counter
 * variable) and fork (spawn a token per outgoing flow) — migrated verbatim from the old-engine inline
 * block. Uses {@code Waiting}/{@code Finished}/{@code Continue}/{@code ContinueWithSiblings} directly
 * (not {@code TakeFlows}) because the join-counter bookkeeping and sibling-token creation are
 * node-specific, not the generic gateway "pick outgoing flows" shape. The plain single-in/single-out
 * (or dead-end) case falls back to {@code TakeFlows} since it is byte-for-byte the same as the
 * old-engine's implicit fallthrough to the shared bottom advance/close logic.
 */
public final class ParallelGatewayBehavior implements NodeBehavior<ParallelGatewayNode> {

    @Override
    public Class<ParallelGatewayNode> nodeType() {
        return ParallelGatewayNode.class;
    }

    @Override
    public NodeResult execute(ParallelGatewayNode node, ExecutionContext ctx) {
        TokenRecord at = ctx.token();
        String instanceId = at.instanceId();

        List<SequenceFlow> incoming = ctx.definition().flows().stream()
                .filter(f -> node.id().equals(f.targetRef()))
                .toList();
        List<SequenceFlow> outgoing = ctx.definition().outgoing(node.id());

        if (incoming.size() > 1) {
            return join(node, ctx, at, instanceId, incoming, outgoing);
        }
        if (outgoing.size() > 1) {
            return fork(ctx, at, instanceId, outgoing);
        }
        // Single (or zero) incoming/outgoing — same as the old-engine's fallthrough to the shared
        // bottom advance/close logic; let the engine's generic TakeFlows handling do it.
        return NodeResult.takeFlows(outgoing);
    }

    private NodeResult join(
            ParallelGatewayNode node, ExecutionContext ctx, TokenRecord at, String instanceId,
            List<SequenceFlow> incoming, List<SequenceFlow> outgoing
    ) {
        String joinKey = "_join_" + node.id();
        Map<String, Object> vars = ctx.vars();
        int arrived = ((Number) vars.getOrDefault(joinKey, 0)).intValue() + 1;
        ctx.variables().putAll(instanceId, Map.of(joinKey, arrived));
        ctx.tokens().save(new TokenRecord(at.id(), instanceId, node.id(), TokenStatus.COMPLETED, null));

        if (arrived < incoming.size()) {
            ctx.completeNodeState();
            return NodeResult.waiting();
        }

        ctx.variables().putAll(instanceId, Map.of(joinKey, 0));
        if (outgoing.isEmpty()) {
            ctx.completeNodeState();
            ctx.maybeCompleteInstance();
            return NodeResult.finished();
        }

        ctx.completeNodeState();
        TokenRecord next = new TokenRecord(
                UUID.randomUUID().toString(), instanceId, outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, null);
        ctx.tokens().save(next);
        return NodeResult.continueWith(next);
    }

    private NodeResult fork(ExecutionContext ctx, TokenRecord at, String instanceId, List<SequenceFlow> outgoing) {
        List<TokenRecord> siblings = new ArrayList<>();
        for (int i = 1; i < outgoing.size(); i++) {
            TokenRecord sibling = new TokenRecord(
                    UUID.randomUUID().toString(), instanceId, outgoing.get(i).targetRef(), TokenStatus.ACTIVE, null);
            ctx.tokens().save(sibling);
            siblings.add(sibling);
        }
        TokenRecord next = new TokenRecord(
                at.id(), instanceId, outgoing.getFirst().targetRef(), TokenStatus.ACTIVE, null);
        ctx.tokens().save(next);
        ctx.completeNodeState();
        return NodeResult.continueWithSiblings(next, siblings);
    }
}
