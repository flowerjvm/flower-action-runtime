package io.github.flowerjvm.flower.action.runtime.audit;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEvent(
        String eventId,
        AuditEventType type,
        String proposalId,
        String actionId,
        String runId,
        String traceId,
        String tenantId,
        String userId,
        Instant occurredAt,
        Map<String, Object> payload) {

    public AuditEvent {
        eventId = eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId.trim();
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        proposalId = proposalId == null ? "" : proposalId.trim();
        actionId = actionId == null ? "" : actionId.trim();
        runId = runId == null ? "" : runId.trim();
        traceId = traceId == null ? "" : traceId.trim();
        tenantId = tenantId == null ? "" : tenantId.trim();
        userId = userId == null ? "" : userId.trim();
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static AuditEvent of(
            AuditEventType type,
            ActionProposal proposal,
            ExecutionContext context,
            Map<String, Object> payload) {
        return new AuditEvent(
                null,
                type,
                proposal == null ? "" : proposal.proposalId(),
                proposal == null ? "" : proposal.actionId(),
                context == null ? "" : context.runId(),
                context == null ? "" : context.traceId(),
                context == null ? "" : context.tenantId(),
                context == null ? "" : context.userId(),
                Instant.now(),
                payload);
    }
}
