package io.github.parkkevinsb.flower.agent.runtime.duplicate;

import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        running.remove(key);
        completed.put(key, result);
    }

    @Override
    public void release(ActionProposal proposal, Throwable cause) {
        running.remove(proposal.idempotencyKey());
    }
}
