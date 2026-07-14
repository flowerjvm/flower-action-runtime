package io.github.flowerjvm.flower.action.runtime.action;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryActionRegistry implements ActionRegistry {
    private final Map<String, ActionExecutor> executors;

    public InMemoryActionRegistry(Collection<? extends ActionExecutor> executors) {
        var byId = new LinkedHashMap<String, ActionExecutor>();
        if (executors != null) {
            for (ActionExecutor executor : executors) {
                if (executor == null) {
                    continue;
                }
                String actionId = executor.definition().actionId();
                ActionExecutor previous = byId.putIfAbsent(actionId, executor);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate action executor: " + actionId);
                }
            }
        }
        this.executors = Map.copyOf(byId);
    }

    @Override
    public Optional<ActionExecutor> findExecutor(String actionId) {
        return Optional.ofNullable(executors.get(actionId));
    }

    @Override
    public List<ActionDefinition> definitions() {
        return executors.values().stream()
                .map(ActionExecutor::definition)
                .toList();
    }
}
