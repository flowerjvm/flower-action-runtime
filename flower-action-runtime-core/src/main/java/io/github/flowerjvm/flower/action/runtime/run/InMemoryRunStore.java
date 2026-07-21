package io.github.flowerjvm.flower.action.runtime.run;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
        ActionRun existing = byId.putIfAbsent(run.runId(), run);
        if (existing != null) {
            throw new IllegalStateException("Action run already exists: " + run.runId());
        }
        return run;
    }

    @Override
    public Optional<ActionRun> find(String runId) {
        return Optional.ofNullable(byId.get(runId));
    }

    @Override
    public boolean compareAndSet(ActionRun expected, ActionRun updated) {
        Objects.requireNonNull(expected, "expected run must not be null");
        Objects.requireNonNull(updated, "updated run must not be null");
        if (!expected.runId().equals(updated.runId())) {
            throw new IllegalArgumentException("compare-and-set run ids must match");
        }
        if (updated.version() != expected.version() + 1L) {
            throw new IllegalArgumentException("updated run version must be expected version + 1");
        }
        AtomicBoolean replaced = new AtomicBoolean();
        byId.compute(expected.runId(), (runId, current) -> {
            if (current == null || current.version() != expected.version()) {
                return current;
            }
            replaced.set(true);
            return updated;
        });
        return replaced.get();
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
