package io.github.flowerjvm.flower.action.runtime.policy;

import java.util.Map;
import java.util.Objects;

public record PolicyDecision(PolicyDecisionType type, String reason, Map<String, Object> metadata) {
    public PolicyDecision {
        type = Objects.requireNonNullElse(type, PolicyDecisionType.DENY);
        reason = reason == null ? "" : reason.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(PolicyDecisionType.ALLOW, "", Map.of());
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(PolicyDecisionType.DENY, reason, Map.of());
    }

    public static PolicyDecision requireApproval(String reason) {
        return new PolicyDecision(PolicyDecisionType.REQUIRE_APPROVAL, reason, Map.of());
    }

    public boolean allowedToExecuteNow() {
        return type == PolicyDecisionType.ALLOW || type == PolicyDecisionType.REQUIRE_DRY_RUN;
    }

    public boolean requiresApproval() {
        return type == PolicyDecisionType.REQUIRE_APPROVAL;
    }
}
