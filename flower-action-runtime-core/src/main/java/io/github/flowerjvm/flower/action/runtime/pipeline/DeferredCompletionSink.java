package io.github.flowerjvm.flower.action.runtime.pipeline;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;

/**
 * Internal callback boundary used by in-process asynchronous executors after the initiating pipeline has finalized.
 */
@FunctionalInterface
public interface DeferredCompletionSink {
    void complete(String runId, String attemptToken, ActionExecutionResult result);

    static DeferredCompletionSink unsupported() {
        return (runId, attemptToken, result) -> {
            throw new IllegalStateException("This action runtime does not support deferred completion");
        };
    }
}
