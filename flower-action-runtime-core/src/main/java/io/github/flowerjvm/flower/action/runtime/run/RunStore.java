package io.github.flowerjvm.flower.action.runtime.run;

import java.util.List;
import java.util.Optional;

/**
 * Stores persistent action-run state.
 *
 * <p>Concurrency model, current version: single-node / single-worker assumption. The event-loop backend serializes
 * resume on one worker thread and resume is idempotent, which prevents double execution within one process. Multi-node
 * deployments, where several processes recover from the same store, can double-execute without compare-and-set or
 * status-transition-guarded updates. That coordination is not provided yet.</p>
 */
public interface RunStore {
    ActionRun create(ActionRun run);

    Optional<ActionRun> find(String runId);

    void update(ActionRun run);

    List<ActionRun> findResumable(String tenantId);

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
            public void update(ActionRun run) {
                // noop
            }

            @Override
            public List<ActionRun> findResumable(String tenantId) {
                return List.of();
            }
        };
    }
}
