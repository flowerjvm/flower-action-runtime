package io.github.flowerjvm.flower.action.runtime;

public interface ActionRuntime {
    ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context);
}
