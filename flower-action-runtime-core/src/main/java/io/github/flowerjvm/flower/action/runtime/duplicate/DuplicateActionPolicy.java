package io.github.flowerjvm.flower.action.runtime.duplicate;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;

/** Reserves and finalizes host-scoped idempotency keys. */
public interface DuplicateActionPolicy {
    DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context);

    void complete(ActionProposal proposal, ExecutionContext context, ActionExecutionResult result);

    /**
     * Releases an accepted reservation without caching a result.
     *
     * <p>Approval waits keep their reservation until the run resumes and reaches a terminal result. Implementations
     * must make this operation idempotent.</p>
     */
    void release(ActionProposal proposal, ExecutionContext context, Throwable cause);

    static DuplicateActionPolicy acceptAll() {
        return new DuplicateActionPolicy() {
            @Override
            public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
                return DuplicateActionDecision.accept();
            }

            @Override
            public void complete(
                    ActionProposal proposal,
                    ExecutionContext context,
                    ActionExecutionResult result) {
                // no-op
            }

            @Override
            public void release(ActionProposal proposal, ExecutionContext context, Throwable cause) {
                // no-op
            }
        };
    }
}
