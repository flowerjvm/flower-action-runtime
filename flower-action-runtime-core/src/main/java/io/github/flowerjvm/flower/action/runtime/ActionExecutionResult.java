package io.github.flowerjvm.flower.action.runtime;

import java.util.Map;
import java.util.Objects;

public record ActionExecutionResult(
        ActionExecutionStatus status,
        String code,
        String message,
        Map<String, Object> output,
        RetryDisposition retryDisposition) {

    public ActionExecutionResult {
        status = Objects.requireNonNullElse(status, ActionExecutionStatus.FAILED);
        code = normalizeCode(code, status);
        message = message == null ? "" : message.trim();
        output = output == null ? Map.of() : Map.copyOf(output);
        retryDisposition = Objects.requireNonNullElse(retryDisposition, RetryDisposition.NEVER);
    }

    /**
     * Compatibility constructor for the 0.1.x result shape.
     */
    public ActionExecutionResult(
            ActionExecutionStatus status,
            String message,
            Map<String, Object> output) {
        this(status, defaultCode(status), message, output, defaultRetryDisposition(status));
    }

    public static ActionExecutionResult succeeded(Map<String, Object> output) {
        return new ActionExecutionResult(
                ActionExecutionStatus.SUCCEEDED,
                "SUCCEEDED",
                "",
                output,
                RetryDisposition.NEVER);
    }

    public static ActionExecutionResult failed(String message) {
        return failed("ACTION_FAILED", message);
    }

    public static ActionExecutionResult failed(String code, String message) {
        return new ActionExecutionResult(
                ActionExecutionStatus.FAILED,
                code,
                message,
                Map.of(),
                RetryDisposition.AFTER_BACKOFF);
    }

    public static ActionExecutionResult denied(String message) {
        return denied("ACTION_DENIED", message);
    }

    public static ActionExecutionResult denied(String code, String message) {
        return new ActionExecutionResult(
                ActionExecutionStatus.DENIED,
                code,
                message,
                Map.of(),
                RetryDisposition.NEVER);
    }

    public static ActionExecutionResult validationFailed(String message) {
        return validationFailed("INPUT_VALIDATION_FAILED", message);
    }

    public static ActionExecutionResult validationFailed(String code, String message) {
        return new ActionExecutionResult(
                ActionExecutionStatus.VALIDATION_FAILED,
                code,
                message,
                Map.of(),
                RetryDisposition.AFTER_CORRECTION);
    }

    public static ActionExecutionResult pendingApproval(String message, Map<String, Object> output) {
        return pendingApproval("APPROVAL_REQUIRED", message, output);
    }

    public static ActionExecutionResult pendingApproval(
            String code,
            String message,
            Map<String, Object> output) {
        return new ActionExecutionResult(
                ActionExecutionStatus.PENDING_APPROVAL,
                code,
                message,
                output,
                RetryDisposition.AFTER_APPROVAL);
    }

    public static ActionExecutionResult accepted(String message, Map<String, Object> output) {
        return accepted("ACTION_ACCEPTED", message, output);
    }

    public static ActionExecutionResult accepted(String code, String message, Map<String, Object> output) {
        return new ActionExecutionResult(
                ActionExecutionStatus.ACCEPTED,
                code,
                message,
                output,
                RetryDisposition.NEVER);
    }

    public static ActionExecutionResult cancelled(String message) {
        return cancelled("ACTION_CANCELLED", message);
    }

    public static ActionExecutionResult cancelled(String code, String message) {
        return new ActionExecutionResult(
                ActionExecutionStatus.CANCELLED,
                code,
                message,
                Map.of(),
                RetryDisposition.NEVER);
    }

    public boolean terminalSuccess() {
        return status == ActionExecutionStatus.SUCCEEDED;
    }

    public boolean terminal() {
        return status.isTerminal();
    }

    private static String normalizeCode(String code, ActionExecutionStatus status) {
        String normalized = code == null ? "" : code.trim();
        return normalized.isBlank() ? defaultCode(status) : normalized;
    }

    private static String defaultCode(ActionExecutionStatus status) {
        ActionExecutionStatus safeStatus = Objects.requireNonNullElse(status, ActionExecutionStatus.FAILED);
        return switch (safeStatus) {
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "ACTION_FAILED";
            case DENIED -> "ACTION_DENIED";
            case VALIDATION_FAILED -> "INPUT_VALIDATION_FAILED";
            case PENDING_APPROVAL -> "APPROVAL_REQUIRED";
            case ACCEPTED -> "ACTION_ACCEPTED";
            case CANCELLED -> "ACTION_CANCELLED";
        };
    }

    private static RetryDisposition defaultRetryDisposition(ActionExecutionStatus status) {
        ActionExecutionStatus safeStatus = Objects.requireNonNullElse(status, ActionExecutionStatus.FAILED);
        return switch (safeStatus) {
            case FAILED -> RetryDisposition.AFTER_BACKOFF;
            case VALIDATION_FAILED -> RetryDisposition.AFTER_CORRECTION;
            case PENDING_APPROVAL -> RetryDisposition.AFTER_APPROVAL;
            case SUCCEEDED, DENIED, ACCEPTED, CANCELLED -> RetryDisposition.NEVER;
        };
    }
}
