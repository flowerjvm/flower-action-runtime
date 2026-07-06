package io.github.parkkevinsb.flower.agent.runtime.run;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryRunStore implements RunStore {
    private final ConcurrentMap<String, ActionRun> byId = new ConcurrentHashMap<>();

    @Override
    public ActionRun create(ActionRun run) {
        byId.put(run.runId(), run);
        return run;
    }

    @Override
    public Optional<ActionRun> find(String runId) {
        return Optional.ofNullable(byId.get(runId));
    }

    @Override
    public void update(ActionRun run) {
        byId.put(run.runId(), run);
    }

    @Override
    public List<ActionRun> findResumable(String tenantId) {
        String normalizedTenantId = tenantId == null ? "" : tenantId.trim();
        return byId.values().stream()
                .filter(run -> run.tenantId().equals(normalizedTenantId))
                .filter(run -> !run.status().isTerminal())
                .toList();
    }
}
