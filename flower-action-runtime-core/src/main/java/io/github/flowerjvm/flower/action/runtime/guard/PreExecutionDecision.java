package io.github.flowerjvm.flower.action.runtime.guard;

import java.util.Map;

/**
 * Final host-controlled check immediately before an action side effect is dispatched.
 */
public record PreExecutionDecision(
        boolean allowed,
        String code,
        String reason,
        Map<String, Object> metadata) {

    public PreExecutionDecision {
        code = normalizeCode(code, allowed);
        reason = reason == null ? "" : reason.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static PreExecutionDecision allow() {
        return new PreExecutionDecision(true, "PRE_EXECUTION_ALLOWED", "", Map.of());
    }

    public static PreExecutionDecision deny(String code, String reason) {
        return new PreExecutionDecision(false, code, reason, Map.of());
    }

    public static PreExecutionDecision deny(
            String code,
            String reason,
            Map<String, Object> metadata) {
        return new PreExecutionDecision(false, code, reason, metadata);
    }

    private static String normalizeCode(String code, boolean allowed) {
        String normalized = code == null ? "" : code.trim();
        if (!normalized.isBlank()) {
            return normalized;
        }
        return allowed ? "PRE_EXECUTION_ALLOWED" : "PRE_EXECUTION_DENIED";
    }
}
