package io.github.flowerjvm.flower.action.runtime.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.util.Map;

public record ActionExecutionContext(
        ExecutionContext executionContext,
        ActionProposal proposal,
        ActionDefinition definition,
        Map<String, Object> input) {

    public ActionExecutionContext {
        if (executionContext == null) {
            throw new IllegalArgumentException("executionContext must not be null");
        }
        if (proposal == null) {
            throw new IllegalArgumentException("proposal must not be null");
        }
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
