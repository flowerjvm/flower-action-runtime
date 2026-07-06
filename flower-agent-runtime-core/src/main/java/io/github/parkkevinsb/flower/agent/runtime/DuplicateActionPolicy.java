package io.github.parkkevinsb.flower.agent.runtime;

public interface DuplicateActionPolicy {
    DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context);

    void complete(ActionProposal proposal, ActionExecutionResult result);

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
        };
    }
}
