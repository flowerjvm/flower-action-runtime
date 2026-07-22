package io.github.flowerjvm.flower.action.runtime;

/**
 * Runtime that can finish or cancel an action after it entered a deferred execution state.
 */
public interface CompletableActionRuntime extends ResumableActionRuntime {
    ActionExecutionResult complete(String runId, String attemptToken, ActionExecutionResult result);

    /**
     * Stops the runtime from accepting a later normal completion for this Run.
     *
     * <p>For asynchronous or external work, executor cancellation hooks are best-effort. A {@code CANCELLED} Run
     * therefore describes the runtime lifecycle, not proof that a remote side effect physically stopped. Callers
     * must inspect the stable result code, retry disposition, and cancellation warning output.</p>
     */
    ActionExecutionResult cancel(String runId, String reason);
}
