package io.github.parkkevinsb.flower.agent.runtime.policy;


import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionDefinition;
public interface PolicyGate {
    PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context);

    static PolicyGate allowAll() {
        return (proposal, definition, context) -> PolicyDecision.allow();
    }

    static PolicyGate denyAll(String reason) {
        return (proposal, definition, context) -> PolicyDecision.deny(reason);
    }
}
