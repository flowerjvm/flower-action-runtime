package io.github.parkkevinsb.flower.agent.runtime;

public interface ActionExecutor {
    ActionDefinition definition();

    ActionExecutionResult execute(ActionExecutionContext context);

    default ActionExecutionResult dryRun(ActionExecutionContext context) {
        return ActionExecutionResult.succeeded(java.util.Map.of("dryRun", true));
    }
}
