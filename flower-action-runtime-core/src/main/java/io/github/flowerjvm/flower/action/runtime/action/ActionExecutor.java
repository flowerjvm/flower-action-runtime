package io.github.flowerjvm.flower.action.runtime.action;


import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
public interface ActionExecutor {
    ActionDefinition definition();

    ActionExecutionResult execute(ActionExecutionContext context);

    /**
     * Dispatches the action using either immediate, in-process asynchronous, or external deferred execution.
     * Existing synchronous executors automatically produce a completed dispatch.
     */
    default ActionDispatch dispatch(ActionExecutionContext context) {
        return ActionDispatch.completed(execute(context));
    }

    default ActionExecutionResult dryRun(ActionExecutionContext context) {
        return ActionExecutionResult.succeeded(java.util.Map.of("dryRun", true));
    }
}
