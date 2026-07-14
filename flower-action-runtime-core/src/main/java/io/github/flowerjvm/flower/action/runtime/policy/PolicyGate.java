package io.github.flowerjvm.flower.action.runtime.policy;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;

/**
 * Evaluates whether a proposed action may proceed, must stop, or must pass through an extra control boundary.
 *
 * <p>Core intentionally does not depend on external policy engines such as OPA or Cerbos. Those integrations should be
 * provided as separate adapter modules that implement this interface and fail closed when the policy engine is
 * unavailable.</p>
 */
public interface PolicyGate {
    PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context);

    static PolicyGate allowAll() {
        return (proposal, definition, context) -> PolicyDecision.allow();
    }

    static PolicyGate denyAll(String reason) {
        return (proposal, definition, context) -> PolicyDecision.deny(reason);
    }
}
