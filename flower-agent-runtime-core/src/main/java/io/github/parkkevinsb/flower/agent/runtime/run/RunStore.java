package io.github.parkkevinsb.flower.agent.runtime.run;

import java.util.List;
import java.util.Optional;

public interface RunStore {
    ActionRun create(ActionRun run);

    Optional<ActionRun> find(String runId);

    void update(ActionRun run);

    List<ActionRun> findResumable(String tenantId);

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
