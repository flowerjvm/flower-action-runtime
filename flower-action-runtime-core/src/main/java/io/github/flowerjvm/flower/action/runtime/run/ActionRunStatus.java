package io.github.flowerjvm.flower.action.runtime.run;

public enum ActionRunStatus {
    REQUESTED,
    VALIDATING,
    POLICY_EVALUATED,
    WAITING_APPROVAL,
    RUNNING,
    SUCCEEDED,
    FAILED,
    DENIED,
    CANCELLED,
    EXPIRED,
    RUNTIME_FAILED;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, DENIED, CANCELLED, EXPIRED, RUNTIME_FAILED -> true;
            default -> false;
        };
    }
}
