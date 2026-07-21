package io.github.flowerjvm.flower.action.runtime.run;

import java.util.List;
import java.util.Optional;

/**
 * Stores persistent action-run state.
 *
 * <p>Every state transition uses a versioned compare-and-set operation. Implementations must provide
 * {@link #compareAndSet(ActionRun, ActionRun)} atomically for the storage scope they represent. Compare-and-set
 * prevents stale writers from overwriting a newer run, but it does not by itself provide distributed work claiming
 * or leases.</p>
 */
public interface RunStore {
    /**
     * Creates a new run. Implementations must fail rather than overwrite an existing run with the same id.
     */
    ActionRun create(ActionRun run);

    Optional<ActionRun> find(String runId);

    /**
     * Atomically replaces {@code expected} with {@code updated} only when the stored version still matches.
     * The update must fail without changing storage when the current version is not {@code expected.version()}.
     * Both values must identify the same Run, and {@code updated.version()} must equal
     * {@code expected.version() + 1}. Implementations must not expose or use an unconditional overwrite path for
     * ordinary runtime transitions.
     */
    boolean compareAndSet(ActionRun expected, ActionRun updated);

    List<ActionRun> findResumable(String tenantId);

    /**
     * Whether this store can look up a run after the initiating call returns. Deferred and asynchronous execution
     * requires this capability; durability across process restarts is still implementation-specific.
     */
    default boolean supportsResumableRuns() {
        return true;
    }

    /**
     * Returns a store that intentionally does not remember runs.
     *
     * <p>This is useful for purely synchronous demos and tests. It must not be used for approval-wait or resume-capable
     * runtimes: a {@code PENDING_APPROVAL} result keeps the duplicate reservation until resume reaches a terminal
     * result, so a non-persistent store can leave the host unable to resume and release that reservation.</p>
     */
    static RunStore noop() {
        return new RunStore() {
            @Override
            public ActionRun create(ActionRun run) {
                return run;
            }

            @Override
            public Optional<ActionRun> find(String runId) {
                return Optional.empty();
            }

            @Override
            public boolean compareAndSet(ActionRun expected, ActionRun updated) {
                if (!expected.runId().equals(updated.runId())) {
                    throw new IllegalArgumentException("compare-and-set run ids must match");
                }
                if (updated.version() != expected.version() + 1L) {
                    throw new IllegalArgumentException("updated run version must be expected version + 1");
                }
                return true;
            }

            @Override
            public List<ActionRun> findResumable(String tenantId) {
                return List.of();
            }

            @Override
            public boolean supportsResumableRuns() {
                return false;
            }
        };
    }
}
