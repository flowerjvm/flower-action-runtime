package io.github.flowerjvm.flower.action.runtime.action;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Convenience contract for bounded in-process asynchronous execution.
 *
 * <p>The executor must use a host-injected bounded executor or a genuinely asynchronous client. It must not create
 * unbounded or per-action thread pools. For work that must survive a JVM restart, use {@link DeferredActionExecutor}
 * and an external durable operation instead.</p>
 */
public interface AsyncActionExecutor extends ActionExecutor {
    CompletionStage<ActionExecutionResult> executeAsync(ActionExecutionContext context);

    default String operationId(ActionExecutionContext context) {
        return context.deterministicOperationId();
    }

    default Instant dueAt(ActionExecutionContext context) {
        return null;
    }

    default Map<String, Object> operationMetadata(ActionExecutionContext context) {
        return Map.of("mode", "in-process-async");
    }

    /**
     * Best-effort cooperative cancellation hook. A late completion is still rejected by the run attempt token.
     * Implementations must be idempotent for the Run and attempt token because separate runtime instances may
     * request cancellation concurrently.
     */
    default void cancel(ActionExecutionContext context, String reason) {
        // Optional host integration.
    }

    @Override
    default ActionDispatch dispatch(ActionExecutionContext context) {
        return ActionDispatch.async(
                executeAsync(context),
                operationId(context),
                dueAt(context),
                operationMetadata(context));
    }

}
