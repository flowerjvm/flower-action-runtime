package io.github.flowerjvm.flower.action.runtime.action;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Host-injected execution lane for short in-process asynchronous actions.
 */
@FunctionalInterface
public interface ActionExecutionDispatcher {
    CompletionStage<ActionExecutionResult> dispatch(Supplier<ActionExecutionResult> task);

    static ActionExecutionDispatcher using(Executor executor) {
        Executor safeExecutor = Objects.requireNonNull(executor, "executor must not be null");
        return task -> CompletableFuture.supplyAsync(
                Objects.requireNonNull(task, "task must not be null"),
                safeExecutor);
    }
}
