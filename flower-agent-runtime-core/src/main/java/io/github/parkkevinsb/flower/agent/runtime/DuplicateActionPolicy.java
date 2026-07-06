package io.github.parkkevinsb.flower.agent.runtime;

public interface DuplicateActionPolicy {
    DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context);

    /**
     * Finish an accepted reservation and cache the final action result for later duplicate requests.
     */
    void complete(ActionProposal proposal, ActionExecutionResult result);

    /**
     * Release an accepted reservation without caching a result.
     *
     * <p>This is used when the runtime envelope fails before a cacheable action result exists, or when an action is
     * suspended at a non-final boundary such as approval wait. Implementations should make this method idempotent.</p>
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
