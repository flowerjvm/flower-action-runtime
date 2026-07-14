package io.github.flowerjvm.flower.action.runtime.policy;

public enum PolicyDecisionType {
    ALLOW,
    DENY,
    REQUIRE_DRY_RUN,
    REQUIRE_APPROVAL,
    REQUIRE_ADDITIONAL_CONTEXT,
    REQUIRE_STRONGER_AUTHENTICATION
}
