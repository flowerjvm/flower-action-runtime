package io.github.flowerjvm.flower.action.runtime.action;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;

/**
 * Dispatches durable or externally executed work and returns the correlation data needed for later completion.
 */
public interface DeferredActionExecutor extends ActionExecutor {
    ActionDispatch.Awaiting dispatchDeferred(ActionExecutionContext context);

    @Override
    default ActionDispatch dispatch(ActionExecutionContext context) {
        return dispatchDeferred(context);
    }

    @Override
    default ActionExecutionResult execute(ActionExecutionContext context) {
        throw new UnsupportedOperationException("Deferred actions must be invoked through dispatch");
    }

    /**
     * Best-effort cooperative cancellation hook. External completion is still protected by the attempt token.
     * Implementations must be idempotent for the same Run attempt and operation id because separate runtime
     * instances may request cancellation concurrently.
     */
    default void cancel(ActionExecutionContext context, String operationId, String reason) {
        // Optional host integration.
    }
}
