package io.github.flowerjvm.flower.action.runtime.duplicate;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory duplicate policy for tests, single-process demos, and local development.
 *
 * <p>This implementation is unbounded: the completed-result map grows without TTL or eviction. Production
 * deployments should use a durable policy with TTL/eviction and tenant/action scoping.</p>
 */
public final class InMemoryDuplicateActionPolicy implements DuplicateActionPolicy {
    private final Map<String, ActionExecutionResult> completed = new ConcurrentHashMap<>();
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    @Override
    public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
        String key = proposal.idempotencyKey();
        ActionExecutionResult result = completed.get(key);
        if (result != null) {
            return DuplicateActionDecision.returnExisting(result);
        }
        Boolean previous = running.putIfAbsent(key, Boolean.TRUE);
        if (previous != null) {
            return DuplicateActionDecision.reject("Duplicate action is already running: " + key);
        }
        return DuplicateActionDecision.accept();
    }

    @Override
    public void complete(ActionProposal proposal, ActionExecutionResult result) {
        String key = proposal.idempotencyKey();
        completed.put(key, result);
        running.remove(key);
    }

    @Override
    public void release(ActionProposal proposal, Throwable cause) {
        running.remove(proposal.idempotencyKey());
    }
}
