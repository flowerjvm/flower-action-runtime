package io.github.flowerjvm.flower.action.runtime.approval;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ApprovalRequest(
        String approvalId,
        String proposalId,
        String reason,
        Instant requestedAt,
        Map<String, Object> snapshot) {

    public ApprovalRequest {
        approvalId = approvalId == null || approvalId.isBlank() ? UUID.randomUUID().toString() : approvalId.trim();
        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId must not be blank");
        }
        proposalId = proposalId.trim();
        reason = reason == null ? "" : reason.trim();
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        snapshot = snapshot == null ? Map.of() : Map.copyOf(snapshot);
    }

    public static ApprovalRequest pending(String proposalId, String reason) {
        return new ApprovalRequest(null, proposalId, reason, Instant.now(), Map.of());
    }
}
