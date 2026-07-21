package io.github.flowerjvm.flower.action.runtime.action;

import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Result of starting an action. A dispatch may finish immediately, continue in-process, or wait for an external
 * completion callback.
 */
public sealed interface ActionDispatch
        permits ActionDispatch.Completed, ActionDispatch.Awaiting, ActionDispatch.Async {

    static Completed completed(ActionExecutionResult result) {
        return new Completed(result);
    }

    static Awaiting awaiting(String operationId, Instant dueAt, Map<String, Object> metadata) {
        return new Awaiting(operationId, dueAt, metadata);
    }

    static Async async(
            CompletionStage<ActionExecutionResult> completion,
            String operationId,
            Instant dueAt,
            Map<String, Object> metadata) {
        return new Async(completion, operationId, dueAt, metadata);
    }

    record Completed(ActionExecutionResult result) implements ActionDispatch {
        public Completed {
            result = Objects.requireNonNull(result, "result must not be null");
        }
    }

    record Awaiting(String operationId, Instant dueAt, Map<String, Object> metadata) implements ActionDispatch {
        public Awaiting {
            operationId = requireOperationId(operationId);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record Async(
            CompletionStage<ActionExecutionResult> completion,
            String operationId,
            Instant dueAt,
            Map<String, Object> metadata) implements ActionDispatch {
        public Async {
            completion = Objects.requireNonNull(completion, "completion must not be null");
            operationId = requireOperationId(operationId);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    private static String requireOperationId(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        return operationId.trim();
    }
}
