package com.bpms.engine.behavior;

import com.bpms.core.definition.MultiInstanceSpec;
import com.bpms.core.definition.StartEventNode;
import com.bpms.core.definition.SubProcessNode;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * subProcess (embedded, plan 34 Phase 1): enters the inner {@code startEvent} on the SAME token/instance —
 * unlike {@link CallActivityBehavior}, no child process instance is spawned. {@link EndEventBehavior}
 * recognizes when an {@code endEvent} belongs to this subprocess
 * ({@link com.bpms.core.definition.ProcessDefinition#containingSubProcess}) and routes the token back out
 * to the subprocess's own outgoing flows instead of closing the whole instance.
 *
 * <p><b>Multi-instance MVP:</b> parallel fan-out only (no {@code sequential=true} loop). {@code
 * loopCardinality} (literal int or expression) or {@code camunda:collection} (a variable holding a
 * {@link Collection}) determines the branch count N. N tokens enter the inner start tagged with
 * {@code parentMultiInstanceId = node.id()}; {@link EndEventBehavior} gates the outer exit behind a
 * {@code _mi_join_<id>} counter until every branch has reached an end event inside this subprocess.
 */
public final class SubProcessBehavior implements NodeBehavior<SubProcessNode> {

    @Override
    public Class<SubProcessNode> nodeType() {
        return SubProcessNode.class;
    }

    @Override
    public NodeResult execute(SubProcessNode node, ExecutionContext ctx) {
        StartEventNode innerStart = node.childNodes().stream()
                .filter(StartEventNode.class::isInstance)
                .map(StartEventNode.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("subProcess " + node.id() + " has no start event"));

        TokenRecord at = ctx.token();
        String instanceId = at.instanceId();
        int n = Math.max(1, node.multiInstance().map(spec -> resolveCardinality(spec, ctx)).orElse(1));

        if (n <= 1) {
            ctx.completeNodeState();
            TokenRecord next = new TokenRecord(at.id(), instanceId, innerStart.id(), TokenStatus.ACTIVE, null);
            ctx.tokens().save(next);
            return NodeResult.continueWith(next);
        }

        ctx.variables().putAll(instanceId, Map.of(
                "_mi_total_" + node.id(), n,
                "_mi_join_" + node.id(), 0));

        List<TokenRecord> siblings = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            TokenRecord sibling = new TokenRecord(
                    UUID.randomUUID().toString(), instanceId, innerStart.id(), TokenStatus.ACTIVE, node.id());
            ctx.tokens().save(sibling);
            siblings.add(sibling);
        }
        TokenRecord next = new TokenRecord(at.id(), instanceId, innerStart.id(), TokenStatus.ACTIVE, node.id());
        ctx.tokens().save(next);
        ctx.completeNodeState();
        return NodeResult.continueWithSiblings(next, siblings);
    }

    private static int resolveCardinality(MultiInstanceSpec mi, ExecutionContext ctx) {
        String cardinality = mi.loopCardinality();
        if (cardinality != null && !cardinality.isBlank()) {
            try {
                return Integer.parseInt(cardinality.trim());
            } catch (NumberFormatException nfe) {
                return toInt(ctx.expressions().evaluate(cardinality, ctx.vars()));
            }
        }
        String collection = mi.collection();
        if (collection != null && !collection.isBlank()) {
            Object val = ctx.vars().get(collection);
            if (val == null) {
                val = ctx.expressions().evaluate(collection, ctx.vars());
            }
            return val instanceof Collection<?> coll ? coll.size() : 0;
        }
        return 1;
    }

    private static int toInt(Object val) {
        if (val instanceof Number n) {
            return n.intValue();
        }
        return val == null ? 0 : Integer.parseInt(String.valueOf(val));
    }
}
