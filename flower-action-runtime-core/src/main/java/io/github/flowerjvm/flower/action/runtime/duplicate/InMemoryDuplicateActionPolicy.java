package io.github.flowerjvm.flower.action.runtime.duplicate;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory duplicate policy for tests, single-process demos, and local development.
 *
 * <p>Reservations are scoped by tenant, action id, and idempotency key so a duplicate result cannot cross those
 * boundaries. This implementation is still unbounded: the completed-result map grows without TTL or eviction.
 * Production deployments should use a durable policy with TTL/eviction and any additional principal or resource
 * scope required by the host.</p>
 */
public final class InMemoryDuplicateActionPolicy implements DuplicateActionPolicy {
    private final Map<DuplicateScope, ActionExecutionResult> completed = new ConcurrentHashMap<>();
    private final Map<DuplicateScope, Boolean> running = new ConcurrentHashMap<>();

    @Override
    public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
        DuplicateScope scope = DuplicateScope.from(proposal, context);
        ActionExecutionResult result = completed.get(scope);
        if (result != null) {
            return DuplicateActionDecision.returnExisting(result);
        }
        Boolean previous = running.putIfAbsent(scope, Boolean.TRUE);
        if (previous != null) {
            return DuplicateActionDecision.reject(
                    "Duplicate action is already running: " + proposal.idempotencyKey());
        }
        return DuplicateActionDecision.accept();
    }

    @Override
    public void complete(
            ActionProposal proposal,
            ExecutionContext context,
            ActionExecutionResult result) {
        completeScope(DuplicateScope.from(proposal, context), result);
    }

    @Override
    public void release(ActionProposal proposal, ExecutionContext context, Throwable cause) {
        releaseScope(DuplicateScope.from(proposal, context));
    }

    private void completeScope(
            DuplicateScope scope,
            ActionExecutionResult result) {
        completed.put(scope, result);
        running.remove(scope);
    }

    private void releaseScope(DuplicateScope scope) {
        running.remove(scope);
    }

    private record DuplicateScope(String tenantId, String actionId, String idempotencyKey) {
        private static DuplicateScope from(ActionProposal proposal, ExecutionContext context) {
            String tenantId = context.tenantId() == null ? "" : context.tenantId().trim();
            return new DuplicateScope(tenantId, proposal.actionId(), proposal.idempotencyKey());
        }
    }
}
