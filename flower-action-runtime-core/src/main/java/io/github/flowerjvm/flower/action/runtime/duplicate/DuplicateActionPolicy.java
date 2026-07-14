package io.github.flowerjvm.flower.action.runtime.duplicate;


import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
public interface DuplicateActionPolicy {
    DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context);

    /**
     * Finish an accepted reservation and cache the final action result for later duplicate requests.
     */
    void complete(ActionProposal proposal, ActionExecutionResult result);

    /**
     * Release an accepted reservation without caching a result.
     *
     * <p>This is used when the runtime envelope fails before a cacheable action result exists. Approval waits do not
     * release the reservation; they keep the idempotency key reserved until the run resumes and reaches a terminal
     * result. Implementations should make this method idempotent.</p>
     */
    void release(ActionProposal proposal, Throwable cause);

    static DuplicateActionPolicy acceptAll() {
        return new DuplicateActionPolicy() {
            @Override
            public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
                return DuplicateActionDecision.accept();
            }

            @Override
            public void complete(ActionProposal proposal, ActionExecutionResult result) {
                // no-op
            }

            @Override
            public void release(ActionProposal proposal, Throwable cause) {
                // no-op
            }
        };
    }
}
