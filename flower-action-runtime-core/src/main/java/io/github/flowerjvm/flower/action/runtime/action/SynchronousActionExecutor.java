package io.github.flowerjvm.flower.action.runtime.action;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;

/** Executes short, bounded work and returns its terminal result in the initiating call. */
public interface SynchronousActionExecutor extends ActionExecutor {
    ActionExecutionResult execute(ActionExecutionContext context);

    @Override
    default ActionDispatch dispatch(ActionExecutionContext context) {
        return ActionDispatch.completed(execute(context));
    }
}
