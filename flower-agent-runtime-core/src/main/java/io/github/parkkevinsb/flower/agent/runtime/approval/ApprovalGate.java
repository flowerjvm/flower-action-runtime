package io.github.parkkevinsb.flower.agent.runtime.approval;


import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionDefinition;
import io.github.parkkevinsb.flower.agent.runtime.policy.PolicyDecision;
public interface ApprovalGate {
    ApprovalRequest requestApproval(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context,
            PolicyDecision policyDecision);

    static ApprovalGate unsupported() {
        return (proposal, definition, context, policyDecision) ->
                ApprovalRequest.pending(proposal.proposalId(), "approval-gate-not-configured");
    }
}
