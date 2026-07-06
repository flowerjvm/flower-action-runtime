package io.github.parkkevinsb.flower.agent.runtime;

public enum ActionExecutionStatus {
    SUCCEEDED,
    FAILED,
    DENIED,
    VALIDATION_FAILED,
    PENDING_APPROVAL,
    DUPLICATE
}
