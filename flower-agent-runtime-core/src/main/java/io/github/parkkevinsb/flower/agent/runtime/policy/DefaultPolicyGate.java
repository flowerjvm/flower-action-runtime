package io.github.parkkevinsb.flower.agent.runtime.policy;


import io.github.parkkevinsb.flower.agent.runtime.ActionOrigin;
import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionDefinition;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionEffect;
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
