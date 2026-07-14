package io.github.flowerjvm.flower.action.runtime;

import java.util.Map;
import java.util.Objects;

public record ActionExecutionResult(
        ActionExecutionStatus status,
        String message,
        Map<String, Object> output) {

    public ActionExecutionResult {
        status = Objects.requireNonNullElse(status, ActionExecutionStatus.FAILED);
        message = message == null ? "" : message.trim();
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    public static ActionExecutionResult succeeded(Map<String, Object> output) {
        return new ActionExecutionResult(ActionExecutionStatus.SUCCEEDED, "", output);
    }

    public static ActionExecutionResult failed(String message) {
        return new ActionExecutionResult(ActionExecutionStatus.FAILED, message, Map.of());
    }

    public static ActionExecutionResult denied(String message) {
        return new ActionExecutionResult(ActionExecutionStatus.DENIED, message, Map.of());
    }

    public static ActionExecutionResult validationFailed(String message) {
        return new ActionExecutionResult(ActionExecutionStatus.VALIDATION_FAILED, message, Map.of());
    }

    public static ActionExecutionResult pendingApproval(String message, Map<String, Object> output) {
        return new ActionExecutionResult(ActionExecutionStatus.PENDING_APPROVAL, message, output);
    }

    public boolean terminalSuccess() {
        return status == ActionExecutionStatus.SUCCEEDED;
    }
}
