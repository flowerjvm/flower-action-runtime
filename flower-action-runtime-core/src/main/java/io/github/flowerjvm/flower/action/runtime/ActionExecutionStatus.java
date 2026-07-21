package io.github.flowerjvm.flower.action.runtime;

public enum ActionExecutionStatus {
    SUCCEEDED,
    FAILED,
    DENIED,
    VALIDATION_FAILED,
    PENDING_APPROVAL,
    ACCEPTED,
    CANCELLED;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, DENIED, VALIDATION_FAILED, CANCELLED -> true;
            case PENDING_APPROVAL, ACCEPTED -> false;
        };
    }
}
