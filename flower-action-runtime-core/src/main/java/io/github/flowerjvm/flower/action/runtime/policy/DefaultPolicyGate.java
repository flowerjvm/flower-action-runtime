package io.github.flowerjvm.flower.action.runtime.policy;


import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
public final class DefaultPolicyGate implements PolicyGate {
    @Override
    public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!definition.allowsRequestChannel(proposal.requestChannel())) {
            return PolicyDecision.deny("Action request channel is not allowed: " + proposal.requestChannel());
        }
        if (!definition.allowsProposerType(proposal.proposerType())) {
            return PolicyDecision.deny("Action proposer type is not allowed: " + proposal.proposerType());
        }
        if (proposal.proposerType() == ActionProposerType.AI_PLANNER
                && definition.effect() != ActionEffect.READ_ONLY) {
            return PolicyDecision.requireApproval("AI planner write actions require approval.");
        }
        if (definition.riskLevel() == ActionRiskLevel.CRITICAL) {
            return PolicyDecision.requireApproval("Critical risk actions require approval.");
        }
        if (definition.riskLevel() == ActionRiskLevel.HIGH && definition.effect() != ActionEffect.READ_ONLY) {
            return PolicyDecision.requireApproval("High risk write actions require approval.");
        }
        if (definition.approvalRequiredByDefault()) {
            return PolicyDecision.requireApproval("Action requires approval by default.");
        }
        return PolicyDecision.allow();
    }
}
