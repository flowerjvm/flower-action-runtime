package io.github.flowerjvm.flower.action.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Proposed business action and its loose input payload.
 *
 * <p>The {@code input} and {@code metadata} maps are intentional MVP flexibility. Core stays free of JSON and typed
 * schema frameworks; use {@code ActionInputValidator} for validation. A typed action adapter can later translate
 * typed request objects to and from these maps at a module boundary.</p>
 */
public record ActionProposal(
        String proposalId,
        String actionId,
        ActionOrigin origin,
        String requesterId,
        String reason,
        double confidence,
        Map<String, Object> input,
        String idempotencyKey,
        Map<String, Object> metadata) {

    public ActionProposal {
        proposalId = proposalId == null || proposalId.isBlank()
                ? UUID.randomUUID().toString()
                : proposalId.trim();
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("actionId must not be blank");
        }
        actionId = actionId.trim();
        origin = Objects.requireNonNullElse(origin, ActionOrigin.UNKNOWN);
        requesterId = requesterId == null ? "" : requesterId.trim();
        reason = reason == null ? "" : reason.trim();
        if (confidence < 0.0d || confidence > 1.0d) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        input = input == null ? Map.of() : Map.copyOf(input);
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? proposalId
                : idempotencyKey.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ActionProposal user(String actionId, Map<String, Object> input, String requesterId) {
        return new ActionProposal(null, actionId, ActionOrigin.USER, requesterId, "", 1.0d, input, null, Map.of());
    }
}
