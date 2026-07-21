package io.github.flowerjvm.flower.action.runtime.guard;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;

/**
 * Rechecks volatile host state immediately before execution begins.
 *
 * <p>Policy answers whether an action is allowed in principle. This guard answers whether it is still safe to
 * execute now: for example, whether the resource version, cancellation state, quota, or domain phase still matches
 * the approved request. Implementations must be quick and side-effect free.</p>
 */
@FunctionalInterface
public interface PreExecutionGuard {
    PreExecutionDecision check(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context,
            PolicyDecision policyDecision);

    static PreExecutionGuard allowAll() {
        return (proposal, definition, context, policyDecision) -> PreExecutionDecision.allow();
    }

    static PreExecutionGuard denyAll(String code, String reason) {
        return (proposal, definition, context, policyDecision) -> PreExecutionDecision.deny(code, reason);
    }
}
