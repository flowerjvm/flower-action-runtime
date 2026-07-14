package io.github.flowerjvm.flower.action.runtime.run;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory run store for tests, single-process demos, and local development.
 *
 * <p>This implementation is an unbounded in-memory map. Runs disappear when the process exits, and there is no
 * TTL, eviction, or cross-process coordination. Production deployments should use a durable implementation such as
 * JDBC.</p>
 */
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
