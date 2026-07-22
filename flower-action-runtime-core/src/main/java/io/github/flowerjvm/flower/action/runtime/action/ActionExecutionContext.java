package io.github.flowerjvm.flower.action.runtime.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.util.Map;

public record ActionExecutionContext(
        ExecutionContext executionContext,
        ActionProposal proposal,
        ActionDefinition definition,
        Map<String, Object> input,
        String attemptToken) {

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
        attemptToken = attemptToken == null ? "" : attemptToken.trim();
    }

    /**
     * Compatibility constructor for synchronous 0.1.x executors.
     */
    public ActionExecutionContext(
            ExecutionContext executionContext,
            ActionProposal proposal,
            ActionDefinition definition,
            Map<String, Object> input) {
        this(executionContext, proposal, definition, input, "");
    }

    /**
     * Builds a deterministic external-operation key for the current Run attempt.
     *
     * <p>Hosts may use this value as an idempotency key when dispatching deferred work. It is correlation data, not
     * callback authentication.</p>
     */
    public String deterministicOperationId() {
        return executionContext.runId() + ":" + attemptToken;
    }
}
