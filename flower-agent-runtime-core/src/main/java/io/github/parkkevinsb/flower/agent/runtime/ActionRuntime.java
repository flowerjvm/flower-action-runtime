package io.github.parkkevinsb.flower.agent.runtime;

public interface ActionRuntime {
    ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context);
}
