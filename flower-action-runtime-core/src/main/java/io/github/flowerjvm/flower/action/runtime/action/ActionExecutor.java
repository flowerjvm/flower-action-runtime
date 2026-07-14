package io.github.flowerjvm.flower.action.runtime.action;


import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
public interface ActionExecutor {
    ActionDefinition definition();

    ActionExecutionResult execute(ActionExecutionContext context);

    default ActionExecutionResult dryRun(ActionExecutionContext context) {
        return ActionExecutionResult.succeeded(java.util.Map.of("dryRun", true));
    }
}
