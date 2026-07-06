package io.github.parkkevinsb.flower.agent.runtime;

public interface PolicyGate {
    PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context);

    static PolicyGate allowAll() {
        return (proposal, definition, context) -> PolicyDecision.allow();
    }

    static PolicyGate denyAll(String reason) {
        return (proposal, definition, context) -> PolicyDecision.deny(reason);
    }
}
