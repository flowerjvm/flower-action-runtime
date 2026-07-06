package io.github.parkkevinsb.flower.agent.runtime;

public final class DefaultPolicyGate implements PolicyGate {
    @Override
    public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!definition.allowsOrigin(proposal.origin())) {
            return PolicyDecision.deny("Action origin is not allowed: " + proposal.origin());
        }
        if (proposal.origin() == ActionOrigin.AI_PLANNER && definition.effect() != ActionEffect.READ_ONLY) {
            return PolicyDecision.requireApproval("AI planner write actions require approval.");
        }
        if (definition.approvalRequiredByDefault()) {
            return PolicyDecision.requireApproval("Action requires approval by default.");
        }
        return PolicyDecision.allow();
    }
}
