package io.github.flowerjvm.flower.action.runtime.approval;

import java.util.Objects;

public record ApprovalDecision(
        ApprovalDecisionType type,
        String approvalId,
        String resolvedBy,
        String reason) {

    public ApprovalDecision {
        type = Objects.requireNonNull(type, "type must not be null");
        if (approvalId == null || approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId must not be blank");
        }
        approvalId = approvalId.trim();
        resolvedBy = resolvedBy == null ? "" : resolvedBy.trim();
        reason = reason == null ? "" : reason.trim();
    }

    public static ApprovalDecision approved(String approvalId, String resolvedBy) {
        return new ApprovalDecision(ApprovalDecisionType.APPROVED, approvalId, resolvedBy, "");
    }

    public static ApprovalDecision rejected(String approvalId, String resolvedBy, String reason) {
        return new ApprovalDecision(ApprovalDecisionType.REJECTED, approvalId, resolvedBy, reason);
    }

    public static ApprovalDecision expired(String approvalId) {
        return new ApprovalDecision(ApprovalDecisionType.EXPIRED, approvalId, "system", "deadline");
    }
}
