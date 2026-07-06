package io.github.parkkevinsb.flower.agent.runtime.action;


import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
public interface ActionExecutor {
    ActionDefinition definition();

    ActionExecutionResult execute(ActionExecutionContext context);

    default ActionExecutionResult dryRun(ActionExecutionContext context) {
        return ActionExecutionResult.succeeded(java.util.Map.of("dryRun", true));
    }
}
