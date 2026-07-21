package io.github.flowerjvm.flower.action.runtime.run;

/**
 * Raised when a run changed after a pipeline stage read it and before that stage committed its transition.
 */
public final class ConcurrentActionRunUpdateException extends RuntimeException {
    public ConcurrentActionRunUpdateException(String runId, long expectedVersion) {
        super("Concurrent update for action run " + runId + " at version " + expectedVersion);
    }
}
