package io.github.flowerjvm.flower.action.runtime.action;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;

/**
 * Common contract for a registered action execution mode.
 *
 * <p>Implement one of {@link SynchronousActionExecutor}, {@link AsyncActionExecutor}, or
 * {@link DeferredActionExecutor} unless a host intentionally provides its own {@link ActionDispatch} mode.</p>
 */
public interface ActionExecutor {
    ActionDefinition definition();

    ActionDispatch dispatch(ActionExecutionContext context);

    default ActionExecutionResult dryRun(ActionExecutionContext context) {
        return ActionExecutionResult.succeeded(java.util.Map.of("dryRun", true));
    }
}
