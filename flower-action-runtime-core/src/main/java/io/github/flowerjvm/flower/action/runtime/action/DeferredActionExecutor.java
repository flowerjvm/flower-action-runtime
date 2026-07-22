package io.github.flowerjvm.flower.action.runtime.action;

/**
 * Dispatches durable or externally executed work and returns the correlation data needed for later completion.
 *
 * <p>The runtime persists {@code RUNNING + attemptToken} before invoking this executor and persists
 * {@code WAITING_EXTERNAL + operationId} after it returns. A process can fail after the external system accepts work
 * but before the second transition commits. Implementations must therefore use a deterministic operation id,
 * idempotent dispatch, authenticated callbacks, and host reconciliation. Use a transactional outbox when the host
 * must atomically connect a database change to external delivery.</p>
 */
public interface DeferredActionExecutor extends ActionExecutor {
    ActionDispatch.Awaiting dispatchDeferred(ActionExecutionContext context);

    @Override
    default ActionDispatch dispatch(ActionExecutionContext context) {
        return dispatchDeferred(context);
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
