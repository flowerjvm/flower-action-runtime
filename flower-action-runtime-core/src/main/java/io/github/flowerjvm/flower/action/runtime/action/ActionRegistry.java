package io.github.flowerjvm.flower.action.runtime.action;

import java.util.List;
import java.util.Optional;

public interface ActionRegistry {
    Optional<ActionExecutor> findExecutor(String actionId);

    default Optional<ActionDefinition> findDefinition(String actionId) {
        return findExecutor(actionId).map(ActionExecutor::definition);
    }

    List<ActionDefinition> definitions();

    default boolean isRegistered(String actionId) {
        return findDefinition(actionId).isPresent();
    }
}
