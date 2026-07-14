package io.github.flowerjvm.flower.action.runtime.duplicate;


import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
public record DuplicateActionDecision(
        DuplicateActionDecisionType type,
        ActionExecutionResult existingResult,
        String reason) {

    public DuplicateActionDecision {
        type = type == null ? DuplicateActionDecisionType.ACCEPT : type;
        reason = reason == null ? "" : reason.trim();
    }

    public static DuplicateActionDecision accept() {
        return new DuplicateActionDecision(DuplicateActionDecisionType.ACCEPT, null, "");
    }

    public static DuplicateActionDecision reject(String reason) {
        return new DuplicateActionDecision(DuplicateActionDecisionType.REJECT, null, reason);
    }

    public static DuplicateActionDecision returnExisting(ActionExecutionResult result) {
        return new DuplicateActionDecision(DuplicateActionDecisionType.RETURN_EXISTING, result, "duplicate");
    }
}
