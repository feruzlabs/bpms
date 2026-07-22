package com.bpms.engine.behavior;

import com.bpms.core.definition.UserTaskNode;
import com.bpms.expression.HumanTaskExpressions;
import com.bpms.spi.engine.RuntimeModels.InstanceStatus;
import com.bpms.spi.engine.RuntimeModels.TokenRecord;
import com.bpms.spi.engine.RuntimeModels.TokenStatus;
import com.bpms.spi.engine.RuntimeModels.UserTaskRecord;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * userTask: resolve assignee/due/priority/candidates, persist the task, and WAIT — token_state stays
 * ACTIVE until {@code ExecutionEngine.continueAfterUserTask} (plan 23) — migrated verbatim.
 *
 * <p>Plan 32 Phase 3: registers any boundary timer/message/signal events attached to this task before
 * parking WAITING — {@code ExecutionEngine.continueAfterUserTask} clears them once the task is completed.
 */
public final class UserTaskBehavior implements NodeBehavior<UserTaskNode> {

    @Override
    public Class<UserTaskNode> nodeType() {
        return UserTaskNode.class;
    }

    @Override
    public NodeResult execute(UserTaskNode user, ExecutionContext ctx) {
        BoundarySupport.registerBoundaryEvents(ctx, user.id());
        ctx.applyIoInputs(user.inputs());
        Map<String, Object> vars = ctx.vars();

        String assignee = HumanTaskExpressions.resolveAssignee(user.assignee(), ctx.expressions(), vars);
        Instant due = HumanTaskExpressions.resolveDueDate(user.dueDate(), ctx.expressions(), vars);
        int priority = HumanTaskExpressions.resolvePriority(user.priority(), ctx.expressions(), vars);
        String formKey = user.formData().map(f -> f.formKey()).orElse(null);

        TokenRecord token = ctx.token();
        ctx.tokens().save(new TokenRecord(
                token.id(), token.instanceId(), token.currentNodeId(), TokenStatus.WAITING, null));
        ctx.instances().save(ctx.withStatus(token.instanceId(), InstanceStatus.WAITING));
        ctx.tasks().save(new UserTaskRecord(
                UUID.randomUUID().toString(), token.instanceId(), token.id(),
                user.id(), user.name(),
                assignee,
                HumanTaskExpressions.resolveCsv(user.candidateGroups(), ctx.expressions(), vars),
                HumanTaskExpressions.resolveCsv(user.candidateUsers(), ctx.expressions(), vars),
                due, priority, formKey, null, null,
                false, ctx.clock().now(), null));

        // Keep token_state ACTIVE until completeTask (same pattern as WAITING_JOB) — no completeNodeState here.
        return NodeResult.waiting();
    }
}
