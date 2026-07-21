package io.github.flowerjvm.flower.action.runtime;

/**
 * Runtime that can finish or cancel an action after it entered a deferred execution state.
 */
public interface CompletableActionRuntime extends ResumableActionRuntime {
    ActionExecutionResult complete(String runId, String attemptToken, ActionExecutionResult result);

    ActionExecutionResult cancel(String runId, String reason);
}
